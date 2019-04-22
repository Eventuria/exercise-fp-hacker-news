import sbt._

object Dependency {

  object Version {
    val `cats-core` = "1.1.0"
    val `cats-collections-core` = "0.7.0"
    val `cats-effect` = "1.0.0"
    val mouse = "0.20"
    val http4s = "0.19.0-M2"

  }

  val cats = Seq(
    "org.typelevel" %% "cats-core" % Version.`cats-core`,
    "org.typelevel" %% "cats-effect" % Version.`cats-effect`)

  val http4s = Seq(
      "org.http4s" %% "http4s-dsl" % Version.http4s,
      "org.http4s" %% "http4s-blaze-client" % Version.http4s,
      "org.http4s" %% "http4s-circe" % Version.http4s)
}

