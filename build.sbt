val Scala213 = "2.13.1"

inThisBuild(
  List(
    organization := "com.kubukoz",
    homepage := Some(url("https://github.com/kubukoz/cats-effect-utils")),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(
      Developer(
        "kubukoz",
        "Jakub Kozłowski",
        "kubukoz@gmail.com",
        url("https://kubukoz.com")
      )
    )
  )
)

val commonSettings = Seq(
  scalaVersion := "0.25.0-bin-20200506-4da1f25-NIGHTLY",
  name := "mio",
  libraryDependencies ++= List(
    "org.typelevel" % "cats-effect_2.13" % "2.1.3" //just for the instances and Resource
  )
)

val mio =
  project.in(file(".")).settings(commonSettings)
