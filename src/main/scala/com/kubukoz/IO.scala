package com.kubukoz

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import scala.concurrent.ExecutionContext
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

sealed trait IO[+A] extends Serializable {
  def flatMap[B](f: A => IO[B]): IO[B] = IO.FlatMap(this, f, cancelable = true)
  def flatten[B](implicit ev: A <:< IO[B]): IO[B] = flatMap(ev)

  def continual[B](f: Either[Throwable, A] => IO[B]): IO[B] = IO.FlatMap(this.attempt, f, cancelable = false)
  def map[B](f: A => B): IO[B] = flatMap(f.andThen(IO.pure))
  def attempt: IO[Either[Throwable, A]] = IO.Attempt(this)

  def *>[B](iob: IO[B]): IO[B] = flatMap(_ => iob)
  def <*[B](iob: IO[B]): IO[A] = flatMap(a => iob *> IO.pure(a))

  def evalOn(ec: ExecutionContext): IO[A] = IO.On(ec, this)
}

object IO {
  final case class Pure[A](a: A) extends IO[A]
  final case class RaiseError(e: Throwable) extends IO[Nothing]
  final case class Attempt[A](self: IO[A]) extends IO[Either[Throwable, A]]
  final case class Delay[A](f: () => A) extends IO[A]
  final case class Async[A](cb: (Either[Throwable, A] => Unit) => Unit) extends IO[A]
  final case class On[A](ec: ExecutionContext, underlying: IO[A]) extends IO[A]
  final case class FlatMap[A, B](ioa: IO[A], f: A => IO[B], cancelable: Boolean) extends IO[B]
  final case object Blocker extends IO[ExecutionContext]
  final case object Executor extends IO[ExecutionContext]
  final case object Scheduler extends IO[ScheduledExecutorService]

  def apply[A](a: => A): IO[A] = delay(a)

  def pure[A](a: A): IO[A] = Pure(a)
  def delay[A](a: => A): IO[A] = Delay(() => a)
  def suspend[A](a: => IO[A]): IO[A] = delay(a).flatten
  def raiseError(e: Throwable): IO[Nothing] = RaiseError(e)
  def fromEither[A](ea: Either[Throwable, A]): IO[A] = ea.fold(raiseError, pure)

  def async[A](cb: (Either[Throwable, A] => Unit) => Unit): IO[A] = Async(cb)

  def scheduler: IO[ScheduledExecutorService] = IO.Scheduler

  def blocking[A](ioa: IO[A]): IO[A] = IO.Blocker.flatMap(ioa.evalOn(_))
  def executor: IO[ExecutionContext] = IO.Executor

  def sleep(units: Long, unit: TimeUnit): IO[Unit] =
    IO.scheduler.flatMap { ses =>
      IO.async[Unit] { cb =>
        //comment for formatting
        //todo cancelable
        val _ = ses.schedule((() => cb(Right(()))): Runnable, units, unit)
      }
    }

  def unsafeRunAsync[A](ioa: IO[A])(cb: Either[Throwable, A] => Unit)(runtime: Runtime): Unit = {
    def doRun[B](iob: IO[B])(cb: Either[Throwable, B] => Unit)(ctx: Context): Unit =
      iob match {
        case Pure(a)       => cb(Right(a))
        case On(ec, io)    => ec.execute(() => doRun(io)(result => ctx.ec.execute(() => cb(result)))(ctx.withExecutor(ec)))
        case Delay(f)      => doRun(IO.pure(f()))(cb)(ctx)
        case RaiseError(e) => cb(Left(e))
        case Executor      => cb(Right(ctx.ec))
        case Blocker       => cb(Right(runtime.blocker))
        case Scheduler     => cb(Right(runtime.scheduler))
        case Attempt(io)   => doRun(io)(r => cb(Right(r)))(ctx)
        case Async(f)      => f(e => ctx.ec.execute(() => cb(e)))

        case f: FlatMap[a, b] =>
          doRun(f.ioa) {
            case Left(e)  => cb(Left(e))
            case Right(v) => doRun(f.f(v))(cb)(ctx)
          }(ctx)

      }

    val rootContext = Context(runtime.ec)

    runtime.ec.execute(() => doRun(ioa)(cb)(rootContext))
  }

  def unsafeRunSync[A](prog: IO[A])(runtime: Runtime): Either[Throwable, A] = {
    val latch = new CountDownLatch(1)
    var value: Option[Either[Throwable, A]] = None

    IO.unsafeRunAsync(prog) { v =>
      value = Some(v)
      latch.countDown()
    }(runtime)

    latch.await()
    value.get
  }

  final case class Runtime(ec: ExecutionContext, scheduler: ScheduledExecutorService, blocker: ExecutionContext)

  final case class Context(ec: ExecutionContext) {
    def withExecutor(ec: ExecutionContext) = copy(ec = ec)
  }
}

object IODemo extends App {
  def putStrLn(s: Any): IO[Unit] = IO(println(s))

  def printThread(tag: String) = IO.suspend(putStrLn(tag + ": " + Thread.currentThread().getName()))

  val blocker = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool(prefixFactory("blocker")))

  def prefixFactory(prefix: String) =
    new ThreadFactory {
      val a = new AtomicInteger(1)
      def newThread(r: Runnable): Thread = new Thread(r, s"$prefix-thread-${a.getAndIncrement()}")
    }

  val newEc = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor(prefixFactory("newEc")))

  val ses = new ScheduledThreadPoolExecutor(1)

  val runtime = IO.Runtime(ExecutionContext.global, ses, blocker)

  val prog =
    for {
      _ <- printThread("foo")
      _ <- IO.blocking(
            printThread("blocking") *> IO.sleep(10L, TimeUnit.MILLISECONDS) *> printThread(
              "after sleep but in blocking"
            )
          )
      _ <- printThread("bar")
      _ <- printThread("before sleep")
      _ <- IO.sleep(500L, TimeUnit.MILLISECONDS)
      _ <- printThread("after sleep")
    } yield 42

  println {
    IO.unsafeRunSync(printThread("before evalOn") *> prog.evalOn(newEc) <* printThread("after evalOn"))(runtime)
  }

  ses.shutdown()
  newEc.shutdown()
  blocker.shutdown()
}
