import org.scalajs.linker.interface.ModuleKind

scalaVersion := "3.8.3"

lazy val sampleFixtureSettings = Seq(
  Test / sourceGenerators += Def.task {
    val directory = (LocalRootProject / baseDirectory).value / "fixtures" / "samples"
    require(directory.isDirectory, s"Sample fixture directory is missing: $directory")
    val filenames = (directory * "*.json").get().filter(_.isFile).map(_.getName).sorted

    def quote(value: String): String = "\"" + value.flatMap {
      case '\\' => "\\\\"
      case '"'  => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case char => char.toString
    } + "\""

    val contents = s"""package rollerderby.mvp
                       |
                       |private[mvp] object SampleFixtures:
                       |  val filenames: Vector[String] = Vector(${filenames.map(quote).mkString(", ")})
                       |  def path(filename: String): String = ${quote(directory.getAbsolutePath + "/")} + filename
                       |""".stripMargin
    val output = (Test / sourceManaged).value / "rollerderby" / "mvp" / "SampleFixtures.scala"
    if (!output.exists || IO.read(output) != contents) IO.write(output, contents)
    Def.uncached(Seq(output))
  }.taskValue
)

lazy val root = project.in(file("."))
  .aggregate(coreJVM, coreJS, crgImportJVM, crgImportJS, cliJVM, cliJS)
  .settings(publish / skip := true)

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("core"))
  .settings(publish / skip := true)

lazy val crgImport = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("crg-import"))
  .dependsOn(core)
  .settings(sampleFixtureSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "ujson" % "4.4.3",
      "org.scalameta" %% "munit" % "1.2.3" % Test
    ),
    publish / skip := true
  )
  .jvmSettings(
    strykerMutate := Seq("shared/src/main/scala/**/*.scala")
  )
  .jsSettings(
    Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )

lazy val cli = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .in(file("cli"))
  .dependsOn(core, crgImport)
  .settings(sampleFixtureSettings)
  .settings(
    name := "roller-derby-mvp",
    Compile / mainClass := Some("rollerderby.mvp.Main"),
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.3" % Test,
    publish / skip := true
  )
  .jvmSettings(
    strykerMutate := Seq("shared/src/main/scala/**/*.scala"),
    Compile / run / fork := true,
    Compile / run / baseDirectory := (LocalRootProject / baseDirectory).value
  )
  .jsSettings(
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule))
  )

lazy val coreJVM = core.jvm
lazy val coreJS = core.js
lazy val crgImportJVM = crgImport.jvm
lazy val crgImportJS = crgImport.js
lazy val cliJVM = cli.jvm
lazy val cliJS = cli.js

lazy val benchmarks = project.in(file("benchmarks"))
  .enablePlugins(JmhPlugin)
  .dependsOn(crgImportJVM)
  .settings(publish / skip := true)
