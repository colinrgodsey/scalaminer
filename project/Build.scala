import sbt._

import Keys._

import sbtassembly.Plugin._
import AssemblyKeys._

object ProjectBuild extends Build {
	val Organization = "com.colingodsey"
	val Version = "0.1"
	val ScalaVersion = "2.10.4"
	val PlatformVersion = "0.4"

	object Dependencies {
		object V {
			val Casbah = "2.4.1"
			val Commons = "2.4"
			val Jetty = "9.0.0.M5"
			val Akka = "2.2.1"
			val SLF4J = "1.6.4"
			val Spray = "1.2.0"
		}

		val deps = Seq(
			"org.slf4j" % "slf4j-api" % V.SLF4J,
			"org.slf4j" % "slf4j-log4j12" % V.SLF4J,
			"org.slf4j" % "jcl-over-slf4j" % V.SLF4J,
			"log4j" % "log4j" % "1.2.17",
			"log4j" % "apache-log4j-extras" % "1.2.17",

			"org.scala-lang" % "scala-reflect" % ScalaVersion,

			"org.scalatest" % "scalatest_2.10" % "1.9" % "test",
			"junit" % "junit" % "4.10" % "test",
			"org.scalacheck" % "scalacheck_2.10" % "1.10.1" % "test",

			"uk.org.lidalia" % "sysout-over-slf4j" % "1.0.2",

			"org.usb4java" % "usb4java" % "1.2.0",

			"com.typesafe.akka" % "akka-actor_2.10" % V.Akka withSources(),
			"com.typesafe.akka" % "akka-contrib_2.10" % V.Akka withSources(),
			"com.typesafe.akka" % "akka-camel_2.10" % V.Akka withSources(),

			"com.typesafe.akka" % "akka-remote_2.10" % V.Akka withSources()
					excludeAll(ExclusionRule(organization = "org.jboss.netty"),
					ExclusionRule(organization = "io.netty")),

			"com.typesafe.akka" % "akka-kernel_2.10" % V.Akka withSources(),
			"com.typesafe.akka" % "akka-slf4j_2.10" % V.Akka withSources(),
			"com.typesafe.akka" % "akka-testkit_2.10" % V.Akka % "test" withSources(),

			"org.syslog4j" % "syslog4j" % "0.9.30",

			"io.spray"            %   "spray-can"     % V.Spray,
			"io.spray"            %   "spray-client"  % V.Spray,
			"io.spray"            %   "spray-routing" % V.Spray,
			"io.spray"            %   "spray-testkit" % V.Spray,

			"io.spray"            %  "spray-json_2.10"    % "1.2.3",

			"com.lambdaworks"   %   "scrypt"    % "1.4.0"
		)
	}

	// -XX:+UseConcMarkSweepGC -XX:+CMSIncrementalMode
	val jvmOpts = """ -XX:ParallelGCThreads=1
    -Xms20m -Xmx256m -XX:MaxPermSize=128m
    -XX:+UseParallelGC -Xminf=10 -Xmaxf=15
    -XX:+AggressiveOpts -server
    -XX:ReservedCodeCacheSize=64m -XX:+UseFastAccessorMethods
    -XX:+BackgroundCompilation -XX:+UseCodeCacheFlushing
    -XX:MaxGCPauseMillis=10 -XX:+UseTLAB
    -XX:+HeapDumpOnOutOfMemoryError
    -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 """ //-XX:+UseG1GC -XX:+UseSerialGC -XX:-UseConcMarkSweepGC  -XX:+UseParallelGC

	lazy val scalaminer = Project(
		id = "scalaminer",
		base = file("."),
		settings = defaultSettings ++ Seq(
			libraryDependencies ++= Dependencies.deps
		)
	)

	lazy val scalaminerUi = Project(
		id = "ui",
		base = file("ui"),
		settings = defaultSettings ++ Seq(
			libraryDependencies ++= Dependencies.deps
		)
	) dependsOn(scalaminer)

	lazy val buildSettings = Defaults.defaultSettings ++ Seq(
		organization := Organization,
		version := Version,
		scalaVersion := ScalaVersion,
		crossPaths := false)

	val versionObjectName = taskKey[String]("Gen'd version object name in org")

	lazy val defaultSettings = buildSettings ++ assemblySettings ++ Seq(
		resolvers += "mvn repo" at "http://repo1.maven.org/maven2/",
		resolvers += "scala tools" at "http://scala-tools.org/repo-releases/",
		resolvers += "gridgain repo" at "http://www.gridgainsystems.com/maven2/",
		resolvers += "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",
		resolvers += "spray repo" at "http://repo.spray.io",

		versionObjectName := "ScalaMinerVersion",

		sourceGenerators in Compile <+= (version, organization,
				sourceManaged in Compile, versionObjectName) map { (v, org, dir, name) =>
			val file = org.split("\\.").foldLeft(dir / "scala")(_ / _) / s"$name.scala"

			IO.write(file,
				s"""package $org
				   |
				   |object $name {
				   |    def str = "$v"
				   |}
				 """.stripMargin)
			Seq(file)
		},

		// compile options
		scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation",
			"-unchecked", //"-optimise",
			"-Xlog-free-terms" //"-feature",
			//"-Ymacro-debug-lite",
			//"-target:jvm-1.7"
		), //, "-Dscalac.patmat.analysisBudget=off"),
		javacOptions ++= Seq("-Xlint:unchecked", "-Xlint:deprecation"))
}

