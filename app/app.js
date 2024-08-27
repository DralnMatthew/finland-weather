/* *** IMPORTS *** */
import http from 'http';
import {WebSocketServer} from 'ws';
import express from 'express';
import kafkaNode from 'kafka-node';


/* *** CONSTANTS *** */
/*
** PORT: port of the WebSocketServer where clients has to connect
** KAFKA_TOPIC: the topic we have to subscribe to to receive 
** KAFAK_HOST: the address of the kafka broker
*/
const PORT = 8001;
const KAFKA_TOPIC = "forecast";
const KAFKA_HOST = "kafka:9092";

/* *** create the app and define the static resources *** */
const app = express();
app.use(express.static("./static"));


/* *** create the server *** */
const server = http.createServer(app);
const socketServer = new WebSocketServer({server: server});

/* *** define what the server has to do on connection of new clients *** */
socketServer.on("request", function(request) {
	/* accept the request */
	const connection = request.accept(null, request.origin);
	
	/* say hello */
	connection.on("message", function(message) {
		console.log("Received message from client: ", message.data);
		connection.sendUTF("Nice to meet you!");
	});
	
	/* close the connection when client disconnects */
	connection.on("close", function(reasonCode, decription) {
		console.log("Client has disconnected");
	});
});


/* *** create and setup the kafka client *** */
const kafkaClient = new kafkaNode.KafkaClient({
	kafkaHost: KAFKA_HOST,
	connectTimeout: 10000,
	requestTimeout: 10000,
	autoConnect: true
});

/* *** define what the kafka receiver has to do on connection *** */
kafkaClient.on("connect", (error) => {
	if(error) {
		console.log("cannot establish connection");
		throw error;
	} else {
		console.log("kafka client is now connected");
	}
});

/* *** define what the kafka receiver has to do when an error happens *** */
kafkaClient.on("error", (error) => {throw error;});

/* *** **
** *** refresh the metadata before sending the first message
** *** refreshing needed to avoid the error BrokerNotAvailableError: Could not find the leader
** *** reference at: https://www.npmjs.com/package/kafka-node#highlevelproducer-with-keyedpartitioner-errors-on-first-send
** *** */
kafkaClient.refreshMetadata([KAFKA_TOPIC], (error) => { 
	if(error) {
		console.log(error);
		throw error;
	} else {
		console.log("successfully refreshed metadata");
	}
});


/* *** create and setup the kafka consumer *** */
const kafkaConsumer = new kafkaNode.Consumer(kafkaClient, [{topic: KAFKA_TOPIC, partition: 0, offset: 0}], {autoCommit: false});
const kafkaOffset = new kafkaNode.Offset(kafkaClient);

/* *** define what the kafka consumer has to do when it receives a message *** */
kafkaConsumer.on("message", (message) => {
	console.log(message);
	console.log(socketServer.clients);
	if(socketServer.clients.size > 0) {
		socketServer.clients.forEach((client) => { client.send(message.value); });
		console.log("Messages sent");
	}
});

/* *** define what the kafka consumer has to do when an error happens *** */
kafkaConsumer.on("error", (error) => {
	console.log(error);
	throw error;
});

/* *** ** 
** *** define what the kafka consumer has to do when it encounters this error.
** *** it is useful when there are some errors in the delivery of the messages
** *** and the kafka consumer can reset to the latest offset available and 
** *** continue with its tasks
** *** */
kafkaConsumer.on("offsetOutOfRange", (topic) => {
	kafkaOffset.fetchLatestOffsets([topic], (error, offsets) => {
		const latestOffset = offsets[topic][0];
		kafkaConsumer.setOffset(topic, 0, latestOffset);
	})
});


/* *** start the server *** */
server.listen(PORT);
console.log("server is started");
