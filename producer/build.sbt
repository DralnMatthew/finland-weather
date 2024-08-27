name := "producer"

version := "1.0"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
	"org.apache.kafka" % "kafka_2.11" % "1.0.0",
	"org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.2",
	"org.scala-lang" % "scala-library" % "2.10.4",
  	"org.apache.logging.log4j" % "log4j-core" % "2.7"
)	
