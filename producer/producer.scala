package producer

import scala.util.parsing.json._
import scala.collection.mutable.ListBuffer
import sys.process._
import java.util.{Date, Properties}
import scala.util.Random

import org.apache.log4j.Logger
import org.apache.log4j.Level

import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord, ProducerConfig}
import kafka.producer.KeyedMessage

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

case class Record(name: String, ts: Double, lon: Double, lat: Double, temp: Double, tempfelt: Double, pressure: Double, humidity: Double, weather: String)

class PARSER[T] { def unapply(a: Any): Option[T] = Some(a.asInstanceOf[T]) }
object MAP extends PARSER[Map[String, Any]]
object LIST extends PARSER[List[Any]]
object STRING extends PARSER[String]
object DOUBLE extends PARSER[Double]

object ScalaWeatherProducer extends App {

	val BROKER_URLS = sys.env("BROKER_URL")
	val TOKEN_KEY = sys.env("WEATHER_API_TOKEN")
	val TOPIC = "weather"
	var CITIES = List[String]()
	val BATCH_SIZE = 20
	val MAX_DELAY = 200

  	Logger.getLogger("org").setLevel(Level.OFF)

	def callPythonExtractor(): List[String] = {
		val fileContent =  new String(Files.readAllBytes(Paths.get("cities.txt")), StandardCharsets.UTF_8)
    	fileContent.split(",").map(_.trim).toList
	}

	def initProducer(brokers: String): KafkaProducer[String, String] = {
		val props = new Properties()
		props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
		props.put(ProducerConfig.CLIENT_ID_CONFIG, "ScalaWeatherProducer")
		props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
		props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
		new KafkaProducer[String, String](props)
	}

	def getDataFromAPI(token: String, cities: String): String = {
		val APIurl = "http://api.openweathermap.org/data/2.5/group?id=" + cities + "&APPID=" + token
		val data = scala.io.Source.fromURL(APIurl).mkString // convert to string
		data
	}

	def parser(string: String): List[Record] = {
		val parsedData = for {
			Some(MAP(message)) <- List(JSON.parseFull(string))
			LIST(relevations) = message("list")
			MAP(relevation) <- relevations
			STRING(cityName) = relevation("name")
			DOUBLE(ts) = relevation("dt")
			MAP(coords) = relevation("coord")
			DOUBLE(longitude) = coords("lon")
			DOUBLE(latitude) = coords("lat")
			MAP(main) = relevation("main")
			DOUBLE(temp) = main("temp")
			DOUBLE(tempfelt) = main("feels_like")
			DOUBLE(pressure) = main("pressure")
			DOUBLE(humidity) = main("humidity")
			LIST(weatherlist) = relevation("weather")
			MAP(weathermap) <- weatherlist
			STRING(weather) = weathermap("main")
		} yield {
			Record(cityName, ts, longitude, latitude, temp, tempfelt, pressure, humidity, weather)
		}

		parsedData
	}

	def buildMessage(data: Record): String = {
		val message = """{"name":""".stripMargin + data.name.toString +
					  ""","ts":""".stripMargin + data.ts.toString +
					  ""","lon":""".stripMargin + data.lon.toString +
					  ""","lat":""".stripMargin + data.lat.toString +
					  ""","temp":""".stripMargin + data.temp.toString +
					  ""","tempfelt":""".stripMargin + data.tempfelt.toString +
					  ""","pressure": """.stripMargin + data.pressure.toString +
					  ""","humidity":""".stripMargin + data.humidity.toString +
					  ""","weather":""".stripMargin + data.weather.toString + "}"
		message
	}

	def main() {
		val producer = initProducer(BROKER_URLS)
		CITIES = callPythonExtractor()

		val batches = CITIES.grouped(BATCH_SIZE).toList.map(batch => batch.mkString(","))
		while(true) {
			for (batch <- batches) {
				val data = parser(getDataFromAPI(TOKEN_KEY, batch))
				for(datapoint <- data) {
					val message = new ProducerRecord[String, String](TOPIC, null, buildMessage(datapoint))
					producer.send(message)
					print(message + "\n")
				}
				Thread.sleep(1000)
			}
		}
		producer.close()
	}

	main()
}
