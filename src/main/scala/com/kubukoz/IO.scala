import cats.syntax.functor._
import scala.language.implicitConversions

trait IO[A] {

  def bracketExit[B]: IO[B] =
    this.map(a => (???): B)
}

object IO {
  implicit def ioFunctor[A]: cats.Functor[IO] = ???
}
