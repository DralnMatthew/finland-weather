name := "consumer"

version := "1.0"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "org.apache.spark" %% "spark-core" % "2.2.1",
  "org.apache.spark" %% "spark-sql" % "2.2.1",
  "org.apache.spark" % "spark-streaming_2.11" % "2.2.1",
  "org.apache.spark" % "spark-sql-kafka-0-10_2.11" % "2.2.1",
  "org.apache.spark" % "spark-streaming-kafka-0-8_2.11" % "2.2.1",
  "org.scala-lang.modules" %% "scala-parser-combinators" % "1.0.2",
  "org.apache.logging.log4j" % "log4j-core" % "2.7",
  ("com.datastax.spark" %% "spark-cassandra-connector" % "2.4.0").exclude("io.netty", "netty-handler"),
  ("com.datastax.cassandra" % "cassandra-driver-core" % "3.1.0").exclude("io.netty", "netty-handler"),
  "org.apache.spark" %% "spark-mllib" % "2.3.0"
)

