import sbt.Keys._

object Project {
  val settings = Seq(organization := "eventuria",
                     version := "0.1",
                     scalaVersion := "2.12.7",
                     scalacOptions ++= Seq(
                       "-feature",
                       "-deprecation",
                       "-unchecked",
                       "-language:postfixOps",
                       "-language:higherKinds",
                       "-Ypartial-unification")

    )
}
