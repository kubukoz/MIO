import cats.syntax.flatMap._
import scala.language.implicitConversions

trait IO[A] {

  def bracketExit[B]: IO[B] =
    this.flatMap(_ => (??? : IO[B]))
}

object IO {
  implicit val ioFlatmap: cats.FlatMap[IO] = ???
}
