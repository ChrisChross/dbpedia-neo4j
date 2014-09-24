import sbt._
import Keys._
import sbtassembly.Plugin.AssemblyKeys._

object Build extends Build {
  import BuildSettings._
  import Dependencies._
  import Library._

  // configure prompt to show current project
  override lazy val settings = super.settings :+ {
    shellPrompt := { s => Project.extract(s).currentProject.id + " > " }
  }

  // -------------------------------------------------------------------------------------------------------------------
  // Root Project
  // -------------------------------------------------------------------------------------------------------------------

  lazy val root = Project("dbpedia-root",file("."))
    .aggregate(dbpediaCore, dbpediaParser, dbpediaLoader, dbpediaImporter, dbpediaNeo4j)
    .settings(basicSettings: _*)
    .settings(noPublishing: _*)


  // -------------------------------------------------------------------------------------------------------------------
  // Modules
  // -------------------------------------------------------------------------------------------------------------------

  lazy val dbpediaCore = Project("dbpedia-core", file("dbpedia-core"))
    .settings(dbpediaModuleSettings: _*)
    .settings(libraryDependencies ++=
      compile(slf4j) ++
      test(scalacheck, scalatest))


  lazy val dbpediaLoader = Project("dbpedia-loader", file("dbpedia-loader"))
    .dependsOn(dbpediaCore)
    .settings(dbpediaModuleSettings: _*)
    .settings(libraryDependencies ++=
      compile(compress) ++
      test(scalacheck, scalatest))


  lazy val dbpediaParser = Project("dbpedia-parser", file("dbpedia-parser"))
    .dependsOn(dbpediaCore, dbpediaLoader)
    .settings(dbpediaModuleSettings: _*)
    .settings(libraryDependencies ++=
      test(scalacheck, scalatest, commonsLang))


  lazy val dbpediaImporter = Project("dbpedia-importer", file("dbpedia-importer"))
    .dependsOn(dbpediaCore, dbpediaLoader, dbpediaParser)
    .settings(dbpediaModuleSettings: _*)
    .settings(libraryDependencies ++=
      compile(disruptor, hppc, metrics, config, scopt) ++
      test(scalacheck, scalatest))


  lazy val dbpediaNeo4j = Project("dbpedia-neo4j", file("dbpedia-neo4j"))
    .dependsOn(dbpediaCore, dbpediaLoader, dbpediaParser, dbpediaImporter)
    .settings(dbpediaModuleSettings: _*)
    .settings(dbpediaAssemblySettings: _*)
    .settings(mainClass in assembly := Some("de.knutwalker.dbpedia.neo4j.ParallelImport"))
    .settings(libraryDependencies ++=
      compile(neo4j, logback) ++
      test(scalacheck, scalatest))

}
