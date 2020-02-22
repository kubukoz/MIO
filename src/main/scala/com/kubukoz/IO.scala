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
import cats.MonadError
import cats.StackSafeMonad
import cats.implicits._
import scala.util.Random
import java.util.concurrent.atomic.AtomicBoolean

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

trait Fiber[+A] extends Serializable {
  def join: IO[Exit[A]]
  def cancel: IO[Unit]
  def id: FiberId
}

final case class FiberId(id: String)

sealed trait IO[+A] extends Serializable {
  def flatMap[B](f: A => IO[B]): IO[B] = IO.FlatMap(this, f)
  def flatten[B](implicit ev: A <:< IO[B]): IO[B] = flatMap(ev)

  def map[B](f: A => B): IO[B] = flatMap(f.andThen(IO.pure))
  def as[B](b: => B): IO[B] = map(_ => b)
  def void: IO[Unit] = this *> IO.unit

  def attempt: IO[Either[Throwable, A]] = IO.Attempt(this)

  def *>[B](iob: IO[B]): IO[B] = flatMap(_ => iob)
  def <*[B](iob: IO[B]): IO[A] = flatMap(a => iob *> IO.pure(a))

  def evalOn(ec: ExecutionContext): IO[A] = IO.On(ec, this)

  def fork: IO[Fiber[A]] = IO.Fork(this)
}

object IO {
  final case class Pure[A](a: A) extends IO[A]
  final case class RaiseError(e: Throwable) extends IO[Nothing]
  final case class Attempt[A](self: IO[A]) extends IO[Either[Throwable, A]]
  final case class Delay[A](f: () => A) extends IO[A]
  final case class Fork[A](self: IO[A]) extends IO[Fiber[A]]
  final case class Async[A](cb: (Either[Throwable, A] => Unit) => IO[Unit]) extends IO[A]
  final case class On[A](ec: ExecutionContext, underlying: IO[A]) extends IO[A]
  final case class FlatMap[A, B](ioa: IO[A], f: A => IO[B]) extends IO[B]
  final case object Canceled extends IO[Nothing]
  final case object Blocker extends IO[ExecutionContext]
  final case object Executor extends IO[ExecutionContext]
  final case object Scheduler extends IO[ScheduledExecutorService]
  final case object Identifier extends IO[FiberId]

  def apply[A](a: => A): IO[A] = delay(a)

  val canceled: IO[Nothing] = IO.Canceled
  val unit: IO[Unit] = IO.pure(())
  def pure[A](a: A): IO[A] = Pure(a)
  def delay[A](a: => A): IO[A] = Delay(() => a)
  def suspend[A](a: => IO[A]): IO[A] = delay(a).flatten
  def raiseError(e: Throwable): IO[Nothing] = RaiseError(e)
  def fromEither[A](ea: Either[Throwable, A]): IO[A] = ea.fold(raiseError, pure)
  def fromExit[A](exit: Exit[A]): IO[A] = exit.fold(pure, raiseError, canceled)

  def async[A](cb: (Either[Throwable, A] => Unit) => IO[Unit]): IO[A] = Async(cb)
  val never: IO[Nothing] = async(_ => IO.unit)

  def fromFuture[A](futurea: IO[Future[A]]): IO[A] = futurea.flatMap { future =>
    future.value match {
      case None =>
        IO.async { cb =>
          future.onComplete(cb.compose(_.toEither))(ExecutionContext.parasitic)
          IO.unit
        }
      case Some(t) => fromEither(t.toEither)
    }
  }

  def blocking[A](ioa: IO[A]): IO[A] = IO.Blocker.flatMap(ioa.evalOn(_))

  val scheduler: IO[ScheduledExecutorService] = IO.Scheduler
  val executor: IO[ExecutionContext] = IO.Executor

  val fiberId: IO[FiberId] = IO.Identifier

  val flipCoin: IO[Boolean] = IO(Random.nextBoolean())

  def sleep(units: Long, unit: TimeUnit): IO[Unit] =
    IO.scheduler.flatMap { ses =>
      IO.async[Unit] { cb =>
        val scheduling = ses.schedule((() => cb(Right(()))): Runnable, units, unit)

        //todo should it be false?
        IO(scheduling.cancel(false)).void
      }
    }

  implicit val ioMonad: MonadError[IO, Throwable] = new StackSafeMonad[IO] with MonadError[IO, Throwable] {
    def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] = fa.flatMap(f)
    def pure[A](x: A): IO[A] = IO.pure(x)
    def raiseError[A](e: Throwable): IO[A] = IO.raiseError(e)
    def handleErrorWith[A](fa: IO[A])(f: Throwable => IO[A]): IO[A] = fa.attempt.flatMap(_.fold(f, pure))
  }

  private val globalFiberId = new AtomicLong(0)
  private def newFiberId() = FiberId("Fiber-" + globalFiberId.getAndIncrement())

  private def unsafeRun[A](ioa: IO[A])(runtime: Runtime): Fiber[A] = {
    val promise = Promise[Exit[A]]()

    val (fiberId, cancelFiber) = unsafeRunAsync(ioa)(promise.success)(runtime)

    new Fiber[A] {
      val join: IO[Exit[A]] = IO.fromFuture(IO.pure(promise.future))
      val cancel: IO[Unit] = cancelFiber
      val id: FiberId = fiberId
    }
  }

  def unsafeRunAsync[A](ioa: IO[A])(cb: Exit[A] => Unit)(runtime: Runtime): (FiberId, IO[Unit]) = {
    val canceled = new AtomicBoolean(false)

    def doRun[B](iob: IO[B])(cb: Exit[B] => Unit)(ctx: Context): Unit = {
      def continue(value: B) = cb(Exit.Succeeded(value))

      iob match {
        //end states
        case Pure(a)       => continue(a)
        case Canceled      => cb(Exit.Canceled)
        case RaiseError(e) => cb(Exit.Failed(e))

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
            case Exit.Canceled     => throw new Exception("cancelation isn't supported yet")
          }(ctx)

        case Fork(self) => cb(Exit.Succeeded(unsafeRun(self)(runtime)))
        case Async(f) =>
          val finalizer = f { asyncResult =>
            //todo this must check for an idempotency flag
            ctx.ec.execute(() => cb(Exit.fromEither(asyncResult)))
          }

          //todo this should be handled on cancel
          val _ = finalizer

        case On(ec, io) =>
          ec.execute(() => doRun(io)(result => ctx.ec.execute(() => cb(result)))(ctx.withExecutor(ec)))

        case next: FlatMap[a, b] =>
          //stack safety? lmaooo
          doRun(next.ioa) {
            case Exit.Succeeded(v)  => doRun(next.f(v))(cb)(ctx)
            case e @ Exit.Failed(_) => cb(e)
            case Exit.Canceled      => throw new Exception("cancelation isn't supported yet")
          }(ctx)

      }
    }

    val rootContext = Context(runtime.ec, newFiberId())

    runtime.ec.execute(() => doRun(ioa)(cb)(rootContext))

    /* todo the second IO must wait for finalizers to finish */
    (rootContext.id, IO(canceled.set(true)))
  }

  //returns: synchronous join, IO with finalizers
  def unsafeRunSync[A](prog: IO[A])(runtime: Runtime): (() => Exit[A], IO[Unit]) = {
    val latch = new CountDownLatch(1)
    var value: Option[Exit[A]] = None

    val (_, finalizers) = IO.unsafeRunAsync(prog) { v =>
      value = Some(v)
      latch.countDown()
    }(runtime)

    val await = () => {
      latch.await()
      value.get
    }

    (await, finalizers)
  }

  final case class Runtime(ec: ExecutionContext, scheduler: ScheduledExecutorService, blocker: ExecutionContext)

  final private case class Context(ec: ExecutionContext, id: FiberId) {
    def withExecutor(ec: ExecutionContext) = copy(ec = ec)
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

  //todo install shutdown hooks etc
  def main(args: Array[String]): Unit = {
    def reportError(e: Throwable) = {
      new Throwable("IOApp#run failed", e).printStackTrace()
      1
    }

    val code =
      try {
        val (awaitExit, finalizers) = IO.unsafeRunSync(run(args.toList))(runtime)

        Runtime.getRuntime().addShutdownHook(new Thread(() => {
          val _ = IO.unsafeRunSync(finalizers)(runtime)
        }))

        awaitExit().fold(identity, reportError, 0)
      } finally {
        scheduler.shutdown()
        blocker.shutdown()
      }

    System.exit(code)
  }
}

object IODemo extends IOApp {

  val newEc = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor(unsafePrefixFactory("newEc")))

  def putStrLn(s: Any): IO[Unit] = IO(println(s))

  def printThread(tag: String) = IO.suspend(putStrLn(tag + ": " + Thread.currentThread().getName()))

  val prog =
    for {
      _ <- printThread("foo")
      _ <- IO.blocking(
            printThread("blocking") *> IO.sleep(10L, TimeUnit.MILLISECONDS) *> printThread(
              "after sleep but in blocking"
            )
          )
      _ <- printThread("bar")
      _ <- IO.fiberId.flatMap(putStrLn)
      _ <- printThread("before sleep")

      prog = IO.sleep(500L, TimeUnit.MILLISECONDS) *> IO.fiberId.flatMap(putStrLn) *> IO
        .flipCoin
        .ifM(IO.fiberId, IO.raiseError(new Throwable("failed coin flip :/")))
      _ <- List.fill(10)(prog).traverse(_.fork).flatMap(_.traverse(_.join)).flatMap(putStrLn(_))
      _ <- printThread("after sleeps")
    } yield 42

  def run(args: List[String]): IO[Int] = {
    printThread("before evalOn") *> prog.evalOn(newEc) <* printThread("after evalOn")
  } <*
    //this should be in `guarantee`, or better, in a Resource
    //but we don't have bracket yet ;)
    IO(newEc.shutdown())

}
