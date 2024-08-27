package sparkstreaming

import scala.util.parsing.json._
import java.util.HashMap
import java.util.Properties

import org.apache.log4j.Logger
import org.apache.log4j.Level

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, ProducerConfig}
import kafka.serializer.{DefaultDecoder, StringDecoder}

import org.apache.spark.streaming.kafka._
import org.apache.spark.rdd.RDD
import org.apache.spark.SparkConf
import org.apache.spark.streaming._
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.storage.StorageLevel

import org.apache.spark.sql.functions._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.streaming.Trigger
import org.apache.spark.sql.cassandra._

import com.datastax.driver.core.{Cluster, Session}
import com.datastax.spark.connector.SomeColumns
import com.datastax.spark.connector._
import com.datastax.spark.connector.streaming._
import com.datastax.spark.connector.cql.CassandraConnector
import com.datastax.spark.connector.japi.CassandraJavaUtil._

import org.apache.spark.ml.feature.{VectorAssembler, OneHotEncoderEstimator, IndexToString, StringIndexerModel}
import org.apache.spark.ml.regression.{GBTRegressor, GBTRegressionModel}
import org.apache.spark.ml.classification.{DecisionTreeClassifier, DecisionTreeClassificationModel}

case class Record(name: String, hour: Double, lon: Double, lat: Double, temp: Double, tempfelt: Double, pressure: Double, humidity: Double, weather: String)
case class Result(lon: Double, lat: Double, cweather: String, fweather: String, ctemp: Double, ftemp: Double)

object ScalaWeatherForecast extends App {

	val BROKER_URL = sys.env("BROKER_URL")
	val ZOOKEEPER_URL = sys.env("ZOOKEEPER_URL")
	val CASSANDRA_HOST = sys.env("CASSANDRA_HOST")
	val RECEIVER_TOPIC = "weather"
	val SENDER_TOPIC = "forecast"
	val CASSANDRA_KS = "weatherks"
	val CASSANDRA_TB = "relevationstb"

	Logger.getLogger("org").setLevel(Level.OFF)
	

	def parser(string: String): Record = {
		val newmap = string.substring(1, string.length - 1)
				.split(",")
				.map(_.split(":"))
				.map{case Array(k, v) => (k.substring(1, k.length-1), v)}
				.toMap
				
		var weather = newmap("weather").toString
		if(weather == "Smoke" || weather == "Haze" || weather == "Mist") {
			weather = "Fog"
		}
		if(weather == "Drizzle" || weather == "Thunderstorm") {
			weather = "Rain"
		}
		
		var hour = newmap("ts").toDouble
		hour = hour % 86400
		if(hour < 0) {
			hour += 86400
		}
		hour = scala.math.floor(hour/3600) + 3.0

		Record(newmap("name"), hour.toDouble, newmap("lon").toDouble, newmap("lat").toDouble, newmap("temp").toDouble, newmap("tempfelt").toDouble, newmap("pressure").toDouble, newmap("humidity").toDouble, weather)
	}

	def initSession(): SparkSession = {
		val ss = SparkSession.builder()
					.appName("WeatherForecast")
					.config("spark.cassandra.connection.host", CASSANDRA_HOST)
		            .config("spark.cassandra.connection.port", "9042")
					.master("local[2]")
					.getOrCreate()
		import ss.implicits._
		ss
	}

	def initContext(session: SparkSession): StreamingContext = {
		val ssc = new StreamingContext(session.sparkContext, Seconds(5))
		ssc.checkpoint("file:///tmp/spark/checkpoint")
		ssc
	}

	def initKafkaReceiver(context: StreamingContext): InputDStream[(String, String)] = {
		val conf = Map(
			"metadata.broker.list" -> BROKER_URL,
			"zookeeper.connect" -> ZOOKEEPER_URL,
			"group.id" -> "kafka-spark-streaming",
			"zookeeper.connection.timeout.ms" -> "15000")
		val topics = Set(RECEIVER_TOPIC)
		val input = KafkaUtils.createDirectStream[String, String, StringDecoder, StringDecoder](context, conf, topics)
		input
	}

	def initKafkaProducer(brokers: String): KafkaProducer[String, String] = {
		val props = new Properties()
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
		props.put(ProducerConfig.CLIENT_ID_CONFIG, "ScalaForecastProducer")
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
		new KafkaProducer[String, String](props)
	}

	def buildMessage(data: Result): String = {
		val message = """{"lon":""".stripMargin + data.lon.toString +
					  ""","lat":""".stripMargin + data.lat.toString +
					  ""","cweather":""".stripMargin + "\"" + data.cweather.toString + "\"" +
					  ""","fweather":""".stripMargin + "\"" + data.fweather.toString + "\"" +
					  ""","ctemp":""".stripMargin + data.ctemp.toString +
					  ""","ftemp":""".stripMargin + data.ftemp.toString + "}"
		message
	}

	def initCassandra(): Session = {
		val cluster = Cluster.builder().addContactPoint(CASSANDRA_HOST).build()
		val cs = cluster.connect()
		val ks_query = new StringBuilder("CREATE KEYSPACE ")
							.append(CASSANDRA_KS)
							.append(" WITH replication={'class':'SimpleStrategy','replication_factor':1};")
		try {
			cs.execute(ks_query.toString)
		} catch {
			case x: com.datastax.driver.core.exceptions.AlreadyExistsException => {
				print("Keyspace " + CASSANDRA_KS + " already exists!\n")
			}
		}
		val use_ks_query = new StringBuilder("USE ").append(CASSANDRA_KS)
		val tb_query = new StringBuilder("CREATE TABLE ")
							.append(CASSANDRA_TB)
							.append(" (name text, hour double, lon double, lat double, temp double, tempfelt double, pressure double, humidity double, weather text, PRIMARY KEY((name, hour)));")
		val session = cluster.connect(CASSANDRA_KS)
		try {
			cs.execute(use_ks_query.toString)
			cs.execute(tb_query.toString)
		} catch {
			case x: com.datastax.driver.core.exceptions.AlreadyExistsException => {
				print("Table " + CASSANDRA_KS + "." + CASSANDRA_TB + " already exists!\n")
			}
		}
		cs
	}

	def storeToCassandra(rdd: RDD[(String, String)]): Unit = {
		val records = rdd.map(w => parser(w._2))
						 .map(r => (r.name, r.hour, r.lon, r.lat, r.temp, r.tempfelt, r.pressure, r.humidity, r.weather));
		records.saveToCassandra(CASSANDRA_KS, CASSANDRA_TB, SomeColumns("name", "hour", "lon", "lat", "temp", "tempfelt", "pressure", "humidity", "weather"))
	}


	/* *** function to load the regression model previously trained *** */
	def loadRegressor(): GBTRegressionModel = {
		val model = GBTRegressionModel.load("gbt_temp.model")
		model
	}


	/* *** function to load the classification model previously trained *** */
	def loadClassifier(): DecisionTreeClassificationModel = {
		val model = DecisionTreeClassificationModel.load("dt_weather.model")
		model
	}


	/* *** function to load the label indexer model previously trained *** */
	def loadIndexer(): StringIndexerModel = {
		val model = StringIndexerModel.load("weatherIndexer.model")
		model.setHandleInvalid("skip")
		model
	}
	
	
	/* *** function to load the label converter model previously trained *** */
	def loadConverter(indexer: StringIndexerModel): IndexToString = {
		val model = IndexToString.load("labelConverter.model")
		model.setLabels(Array("Clouds", "Clear", "Fog", "Rain", "Snow"))
		model
	}

    /* *** function to prepare the dataset for the regression *** */
	def prepareRegression(input: DataFrame, indexer: StringIndexerModel): DataFrame = {
		var df = input
		
		try {			
			df = indexer.transform(df)
			var encoder = new OneHotEncoderEstimator()
				.setInputCols(Array(indexer.getOutputCol))
				.setOutputCols(Array("WeatherEncoded"))
			df = encoder.fit(df).transform(df)
		} catch {
			case ex: java.lang.IllegalArgumentException => {
				df = df.withColumn("weather", lit(1.0))
				var encoder = new OneHotEncoderEstimator()
					.setInputCols(Array("weather"))
					.setOutputCols(Array("WeatherEncoded"))
				df = encoder.fit(df).transform(df)
			}
		}
		var assembler = new VectorAssembler()
			.setInputCols(Array("lon", "lat", "hour", "temp", "tempfelt", "pressure", "humidity", "WeatherEncoded"))
			.setOutputCol("features")
		df = assembler.transform(df)	
		df
	}
	
	
	/* *** function to prepare the dataset for the classification *** */
	def prepareClassification(input: DataFrame): DataFrame = {
		var df = input
		
		var assembler = new VectorAssembler()
			.setInputCols(Array("lon", "lat", "hour", "temp", "tempfelt", "pressure", "humidity", "temp_forecast", "WeatherEncoded"))
			.setOutputCol("features")
		
		df = assembler.transform(df)
		df
	}
		
	def predict(input: DataFrame, classifier: DecisionTreeClassificationModel, regressor: GBTRegressionModel, indexer: StringIndexerModel, converter: IndexToString): DataFrame = {
		
		var df1 = prepareRegression(input, indexer)
		val regression = regressor.transform(df1).withColumnRenamed("features", "old_features").withColumnRenamed("prediction", "temp_forecast")
		
		val df2 = prepareClassification(regression)
		val classificationRaw = classifier.transform(df2)
		val classification = converter.transform(classificationRaw)
		
		val output = classification.select(col("lon"), col("lat"), col("weather"), col("predictedLabel"), col("temp"), col("temp_forecast"))
		output
	}

	def main() {
		val ss = initSession()
		val ssc = initContext(ss)
		val cs = initCassandra()

		val regressor = loadRegressor()
		val classifier = loadClassifier()
		val indexer = loadIndexer()
		val converter = loadConverter(indexer)

		val input = initKafkaReceiver(ssc)

		input.foreachRDD(rdd => {
			if(!rdd.isEmpty()) {
				storeToCassandra(rdd)
				val rdd2 = rdd.map(data => {
					parser(data._2)
				})

				val df = ss.createDataFrame(rdd2)
				val results = predict(df, classifier, regressor, indexer, converter)

				/* send the messages to the visualization level of our architecture using kafka */
				if(!results.head(1).isEmpty) {
					results.foreach(data => {
						val producer = initKafkaProducer(BROKER_URL)
						val result = Result(data(0).toString.toDouble, data(1).toString.toDouble, data(2).toString, data(3).toString, data(4).toString.toDouble, data(5).toString.toDouble)
						val message = new ProducerRecord[String, String](SENDER_TOPIC, null, buildMessage(result))
						print(message + "\n")
						producer.send(message)
						producer.close()
					})
				}
			}
		})

		ssc.start()
		ssc.awaitTermination()
		cs.close() 
	}

	main()
}
