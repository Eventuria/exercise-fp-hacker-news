
addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)

lazy val `top-commenters-cli` = project
  .settings(Project.settings)
  .settings(
    libraryDependencies ++= Dependency.cats ++ Dependency.http4s
  )

lazy val backend = project.in(file(".")).aggregate(`top-commenters-cli`)