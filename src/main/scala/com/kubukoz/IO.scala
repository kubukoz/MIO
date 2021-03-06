package com.kubukoz

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal
import scala.concurrent.Promise
import scala.concurrent.Future
import cats.StackSafeMonad
import cats.implicits._
import scala.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import cats.~>
import cats.effect.ExitCase
import cats.effect.Resource
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._

sealed trait Exit[+A] extends Product with Serializable {

  def fold[B](succeeded: A => B, failed: Throwable => B, canceled: B): B = this match {
    case Exit.Succeeded(a) => succeeded(a)
    case Exit.Failed(e)    => failed(e)
    case Exit.Canceled     => canceled
  }
}

object Exit {
  final case class Succeeded[A](a: A) extends Exit[A]
  final case class Failed(e: Throwable) extends Exit[Nothing]
  final case object Canceled extends Exit[Nothing]

  def fromEither[A](either: Either[Throwable, A]): Exit[A] = either.fold(Failed(_), Succeeded(_))
}

final case class Fiber[+A](id: FiberId, join: IO[Exit[A]], cancel: IO[Unit])

final case class FiberId(id: Long)

sealed trait IO[+A] extends Serializable {
  def flatMap[B](f: A => IO[B]): IO[B] = IO.FlatMap(this, f)
  def attempt: IO[Either[Throwable, A]] = IO.Attempt(this)
  def evalOn(ec: ExecutionContext): IO[A] = IO.On(ec, this)

  //Forking is uncancelable.
  def fork: IO[Fiber[A]] = IO.Fork(this)
  def exit: IO[Exit[A]] = IO.ExitOf(this)

  def onCancel(cleanup: IO[Unit]): IO[A] =
    bracketExit(_.pure[IO])((_, e) => e.fold(_ => IO.unit, _ => IO.unit, cleanup))

  def bracket[B](use: A => IO[B])(cleanup: A => IO[Unit]): IO[B] = bracketExit(use)((a, _) => cleanup(a))

  def bracketExit[B](use: A => IO[B])(cleanup: (A, Exit[B]) => IO[Unit]): IO[B] = IO.mask { restore =>
    this.flatMap(a => restore(use(a)).exit.flatTap(cleanup(a, _)).flatMap(IO.fromExit))
  }

  def continual[B](f: Either[Throwable, A] => IO[B]): IO[B] = IO.mask(_(attempt).flatMap(f))

  def uncancelable: IO[A] = IO.mask(_ => this)
}

object IO {
  final private case class Pure[A](a: A) extends IO[A]
  final private case class RaiseError(e: Throwable) extends IO[Nothing]
  final private case class Attempt[A](self: IO[A]) extends IO[Either[Throwable, A]]
  final private case class Delay[A](f: () => A) extends IO[A]
  final private case class Fork[A](self: IO[A]) extends IO[Fiber[A]]
  final private case object Yield extends IO[Unit]
  final private case class Async[A](cb: (Either[Throwable, A] => Unit) => IO[IO[Unit]]) extends IO[A]
  final private case class On[A](ec: ExecutionContext, underlying: IO[A]) extends IO[A]
  final private case class FlatMap[A, B](ioa: IO[A], f: A => IO[B]) extends IO[B]
  final private case class AskCancelability[A](ioa: Boolean => IO[A]) extends IO[A]
  final private case class WithCancelability[A](ioa: IO[A], isCancelable: Boolean) extends IO[A]
  final private case class ExitOf[A](ioa: IO[A]) extends IO[Exit[A]]
  final private case object Canceled extends IO[Nothing]
  //runtime
  final private case object Blocker extends IO[ExecutionContext]
  final private case object Scheduler extends IO[ScheduledExecutorService]
  //context
  final private case object Executor extends IO[ExecutionContext]
  final private case object Identifier extends IO[FiberId]

  def apply[A](a: => A): IO[A] = delay(a)

  val canceled: IO[Nothing] = IO.Canceled
  val unit: IO[Unit] = IO.pure(())
  def pure[A](a: A): IO[A] = Pure(a)
  def delay[A](a: => A): IO[A] = Delay(() => a)
  def suspend[A](a: => IO[A]): IO[A] = delay(a).flatten
  def raiseError(e: Throwable): IO[Nothing] = RaiseError(e)
  def fromEither[A](ea: Either[Throwable, A]): IO[A] = ea.fold(raiseError, pure)
  def fromExit[A](exit: Exit[A]): IO[A] = exit.fold(pure, raiseError, canceled)

  type Restore = IO ~> IO

  //Uncancelable block, with cancelability of the enclosing block restored in the blocks wrapped with `Restore`.
  def mask[A](use: Restore => IO[A]): IO[A] = mask(use, false)

  //Cancelable block, with blocks surrounded by restore(...) inheriting cancelability of enclosing block.
  def maskCancelable[A](use: Restore => IO[A]): IO[A] = mask(use, true)

  //A block with cancelatility set by default to the given flag.
  def mask[A](use: Restore => IO[A], default: Boolean): IO[A] = askCancelability { inherited =>
    val restore: Restore = λ[IO ~> IO](WithCancelability(_, inherited))

    WithCancelability(use(restore), default)
  }

  //Provide the curent cancelability status to a block. There's no cancelation possible between checking the status and starting the action in `f`.
  def askCancelability[A](f: Boolean => IO[A]): IO[A] = AskCancelability(f)
  //outer effect registers the callback, inner effect cancels
  def async[A](cb: (Either[Throwable, A] => Unit) => IO[IO[Unit]]): IO[A] = Async(cb)
  val never: IO[Nothing] = async(_ => IO.pure(IO.unit))
  val cede: IO[Unit] = Yield

  def fromFuture[A](futurea: IO[Future[A]]): IO[A] = futurea.flatMap { future =>
    future.value match {
      case None =>
        IO.async[A] { cb =>
          IO {
            future.onComplete(cb.compose(_.toEither))(ExecutionContext.parasitic)
            IO.unit
          }
        }
      case Some(t) => fromEither(t.toEither)
    }
  }

  def blocking[A](ioa: IO[A]): IO[A] = IO.Blocker.flatMap(ioa.evalOn(_))

  val scheduler: IO[ScheduledExecutorService] = IO.Scheduler
  val executor: IO[ExecutionContext] = IO.Executor

  val fiberId: IO[FiberId] = IO.Identifier

  val flipCoin: IO[Boolean] = IO(Random.nextBoolean())

  def sleep(duration: FiniteDuration): IO[Unit] =
    IO.scheduler.flatMap { ses =>
      IO.async[Unit] { cb =>
        IO {
          ses.schedule((() => cb(Right(()))): Runnable, duration.length, duration.unit)
        }.map { scheduling =>
          //todo should it be false?
          IO(scheduling.cancel(false)).void
        }
      }
    }

  implicit val ioAsync: cats.effect.Async[IO] = new cats.effect.Async[IO] with StackSafeMonad[IO] {
    def pure[A](x: A): IO[A] = IO.pure(x)
    def raiseError[A](e: Throwable): IO[A] = IO.raiseError(e)
    def handleErrorWith[A](fa: IO[A])(f: Throwable => IO[A]): IO[A] = fa.attempt.flatMap(_.fold(f, pure))
    def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] = fa.flatMap(f)

    def bracketCase[A, B](acquire: IO[A])(use: A => IO[B])(release: (A, ExitCase[Throwable]) => IO[Unit]): IO[B] =
      acquire.bracketExit(use) { (a, exit) =>
        val exitCase = exit match {
          case Exit.Canceled     => ExitCase.Canceled
          case Exit.Failed(e)    => ExitCase.Error(e)
          case Exit.Succeeded(_) => ExitCase.Completed
        }
        release(a, exitCase)
      }

    def suspend[A](thunk: => IO[A]): IO[A] = IO.suspend(thunk)
    def async[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A] = asyncF { cb => k(cb); unit }
    def asyncF[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): IO[A] = IO.async(k(_).as(IO.unit))
  }

  private val globalFiberId = new AtomicLong(0)
  private def newFiberId() = FiberId(globalFiberId.getAndIncrement())

  private def unsafeRun[A](ioa: IO[A])(runtime: Runtime): Fiber[A] = {
    val promise = Promise[Exit[A]]()

    val (fiberId, cancelFiber) = unsafeRunAsync(ioa)(promise.success)(runtime)

    val join = IO.fromFuture(IO.pure(promise.future))

    Fiber(fiberId, join, cancelFiber)
  }

  //thread safe, mutable
  private class FinalizerStack(var stack: AtomicReference[List[IO[Unit]]]) {

    def push(finalizer: IO[Unit]): Unit = { val _ = stack.updateAndGet(finalizer :: _) }

    def popAll(): List[IO[Unit]] = stack.getAndSet(Nil).reverse
  }

  private object FinalizerStack {
    def initial(): FinalizerStack = new FinalizerStack(new AtomicReference(Nil))
  }

  def unsafeRunAsync[A](ioa: IO[A])(cb: Exit[A] => Unit)(runtime: Runtime): (FiberId, IO[Unit]) = {
    val canceled = new AtomicBoolean(false)

    //must be thread safe
    val finalizers: FinalizerStack = FinalizerStack.initial()

    def doRun[B](iob: IO[B])(cb: Exit[B] => Unit)(ctx: Context): Unit = {
      def continue(value: B) = cb(Exit.Succeeded(value))

      iob match {
        //end states
        case Pure(a)       => continue(a)
        case Canceled      => cb(Exit.Canceled)
        case RaiseError(e) => cb(Exit.Failed(e))
        case ex: ExitOf[a] => doRun(ex.ioa)(continue)(ctx)

        //sync FFI
        case Delay(f) =>
          try continue(f())
          catch { case NonFatal(e) => cb(Exit.Failed(e)) }

        //context/runtime values
        case Executor   => continue(ctx.ec)
        case Identifier => continue(ctx.id)
        case Blocker    => continue(runtime.blocker)
        case Scheduler  => continue(runtime.scheduler)
        case a: Attempt[b] =>
          doRun(a.self) {
            case Exit.Succeeded(r) => continue(Right(r))
            case Exit.Failed(e)    => continue(Left(e))
            case Exit.Canceled     => cb(Exit.Canceled)
          }(ctx)

        case WithCancelability(block, newCancelable) => doRun(block)(cb)(ctx.withCancelability(newCancelable))
        case AskCancelability(ask)                   => doRun(ask(ctx.cancelable))(cb)(ctx)

        case Yield =>
          ctx.ec.execute { () =>
            if (ctx.cancelable && canceled.get())
              cb(Exit.Canceled)
            else
              continue(())
          }

        case Fork(self) =>
          val child = unsafeRun(self)(runtime)

          //todo: non-daemonic children?

          cb(Exit.Succeeded(child))

        case Async(f) =>
          val callbackCalled = new AtomicBoolean(false)

          val callback: Either[Throwable, B] => Unit = asyncResult =>
            //checking idempotency flag - if this is already true, don't do anything
            if (!callbackCalled.getAndSet(true)) {
              finalizers.popAll() //popping and ignoring finalizers - they would've ran only on cancel

              ctx.ec.execute(() => cb(Exit.fromEither(asyncResult)))
            }

          //This will register the callback and finalizers
          val register = f(callback)
            .map { finalizer =>
              IO(callbackCalled.getAndSet(true)).ifM(
                ifFalse = finalizer *> IO(ctx.ec.execute(() => cb(Exit.Canceled))),
                ifTrue = IO.unit
              )
            }
            .flatMap { finalizer =>
              IO.AskCancelability {
                case true =>
                  //This happens if the async call is canceled before the async node is interpreted.
                  //As async tasks check for cancelation after async boundaries (not before), so if the task has already started - we immediately cancel it.
                  if (canceled.get()) {
                    finalizer
                  } else {
                    IO(finalizers.push(finalizer))
                  }
                case false => IO.unit
              }
            }

          doRun(register)(_ => /* todo: report registration/finalizer errors */ ())(ctx)

        case On(ec, io) =>
          def afterwards(result: Exit[B]) = ctx.ec.execute { () =>
            //checking cancelation after shifting back
            if (ctx.cancelable && canceled.get())
              cb(Exit.Canceled)
            else
              cb(result)
          }

          ec.execute { () =>
            //checking cancelation after shifting to new pool
            if (ctx.cancelable && canceled.get())
              cb(Exit.Canceled)
            else
              doRun(io)(afterwards)(ctx.withExecutor(ec))
          }

        case next: FlatMap[a, b] =>
          //stack safety? lmaooo
          doRun(next.ioa) {
            case Exit.Succeeded(v)  => doRun(next.f(v))(cb)(ctx)
            case e @ Exit.Failed(_) => cb(e)
            case Exit.Canceled      => cb(Exit.Canceled)
          }(ctx)

      }
    }

    val rootContext = Context(runtime.ec, newFiberId(), cancelable = true)

    //Always yield before starting
    doRun(IO.cede *> ioa)(cb)(rootContext)

    val awaitFinalizers = IO(finalizers.popAll()).flatMap(_.sequence_)

    val cancel =
      IO(canceled.getAndSet(true)) >>= awaitFinalizers.unlessA

    (rootContext.id, cancel.uncancelable)
  }

  //returns: blocking join, blocking finalizers
  def unsafeRunSync[A](prog: IO[A])(runtime: Runtime): (() => Exit[A], () => Unit) = {
    val latch = new CountDownLatch(1)
    var value: Option[Exit[A]] = None

    val (_, finalizers) = IO.unsafeRunAsync(prog) { v =>
      value = Some(v)
      latch.countDown()
    }(runtime)

    val awaitResult = () => {
      latch.await()
      value.get
    }

    val runFinalizers = () => {
      val (await, _) = unsafeRunSync(finalizers)(runtime)
      val _ = await()

      //Wait for program exit
      val _ = awaitResult()
    }

    (awaitResult, runFinalizers)
  }

  final case class Runtime(ec: ExecutionContext, scheduler: ScheduledExecutorService, blocker: ExecutionContext)

  final private case class Context(ec: ExecutionContext, id: FiberId, cancelable: Boolean) {
    def withExecutor(ec: ExecutionContext) = copy(ec = ec)
    def withCancelability(cancelable: Boolean) = copy(cancelable = cancelable)
  }
}

trait IOApp {

  //internals
  def unsafePrefixFactory(prefix: String) =
    new ThreadFactory {
      val a = new AtomicInteger(1)
      def newThread(r: Runnable): Thread = new Thread(r, s"$prefix-thread-${a.getAndIncrement()}")
    }

  private val blocker =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool(unsafePrefixFactory("blocker")))
  private val scheduler = new ScheduledThreadPoolExecutor(1)
  private val runtime = IO.Runtime(ExecutionContext.global, scheduler, blocker)

  //////////////
  def run(args: List[String]): IO[Int]

  def main(args: Array[String]): Unit = {
    def reportError(e: Throwable) = {
      new Throwable("IOApp#run failed", e).printStackTrace()
      1
    }

    val code =
      try {
        val (awaitExit, finalizers) = IO.unsafeRunSync(run(args.toList))(runtime)

        val hook = new Thread(() => finalizers())

        Runtime.getRuntime().addShutdownHook(hook)

        // //probably not the best way to do it, but...
        awaitExit().fold(identity, reportError, 0)
      } finally {
        try scheduler.shutdown()
        finally blocker.shutdown()
      }

    System.exit(code)
  }
}

object IODemo extends IOApp {

  val newEcResource =
    Resource.make(
      IO(ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor(unsafePrefixFactory("newEc"))))
    )(e => IO(e.shutdown()).void)

  def putStrLn(s: Any): IO[Unit] = IO(println(s))

  def printThread(tag: String) = IO.suspend(putStrLn(tag + ": " + Thread.currentThread().getName()))
  def printFiber(tag: String) = IO.fiberId.flatMap(id => putStrLn(tag + ": " + id))

  val prog =
    for {
      _ <- printThread("newEc")
      _ <- IO.blocking(
            printThread("blocking") *> IO
              .sleep(10.millis) *> IO.raiseError(new Throwable("oops")).attempt *> printThread(
              "after sleep but in blocking"
            )
          )
      _ <- printThread("newEc")
      prog = IO.sleep(500.millis) *>
        IO.fiberId.flatMap(putStrLn) *>
        IO.flipCoin.ifM(printThread("flip") *> IO.fiberId, IO.raiseError(new Throwable("failed coin flip :/")))
      _ <- List.fill(5)(prog).traverse(_.fork).flatMap(_.traverse(_.join)).flatMap(putStrLn(_))
      _ <- printThread("newEc")
    } yield 42

  def run(args: List[String]): IO[Int] =
    // newEcResource.use(newEc =>
    //   printThread("before evalOn: global") *> prog.evalOn(newEc) <* printThread("after evalOn: global")
    // )
    IO.sleep(2.seconds)
      .bracketExit(_ => IO.mask(restore => putStrLn("before sleep") *> IO.never *> putStrLn("after sleep")))((_, e) =>
        putStrLn("exited with " + e)
      )
      .fork
      .flatMap(fib =>
        IO.sleep(500.millis) *> putStrLn("about to cancel") *> fib.cancel *> putStrLn("cancel finished") *> fib.join
      )
      .flatMap(a => putStrLn("final result: " + a))
      .as(0)
  // IO.blocking(IO(Thread.sleep(5000)))
  //   .onCancel(putStrLn("oh noes"))
  //   .fork
  //   .flatMap(fib => fib.cancel *> fib.join)
  //   .flatMap(putStrLn)
  //   .as(0)
}
