FROM ubuntu:20.04

# Environment variables
ENV APP_HOME=/home/dataintensive
ENV JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64
ENV SPARK_VERSION=2.4.3
ENV SPARK_HADOOP_VERSION=hadoop2.7
ENV SPARK_HOME=$APP_HOME/spark-${SPARK_VERSION}-bin-${SPARK_HADOOP_VERSION}
ENV PATH=$SPARK_HOME/bin:$PATH

# Set timezone and install essential packages
RUN apt-get update && \
    DEBIAN_FRONTEND="noninteractive" apt-get install -y tzdata wget curl software-properties-common && \
    add-apt-repository ppa:openjdk-r/ppa && \
    apt-get update && \
    apt-get install -y openjdk-8-jdk-headless

# Install SBT from the official SBT repository
RUN apt-get update && \
    apt-get install -y wget && \
    wget https://repo.scala-sbt.org/scalasbt/debian/sbt-1.8.0.deb && \
    dpkg -i sbt-1.8.0.deb && \
    apt-get install -f -y && \
    rm sbt-1.8.0.deb && \
    # Clean up APT cache to reduce image size
    rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR $APP_HOME

# Install Apache Spark
RUN wget https://archive.apache.org/dist/spark/spark-${SPARK_VERSION}/spark-${SPARK_VERSION}-bin-${SPARK_HADOOP_VERSION}.tgz && \
    tar -xvf spark-${SPARK_VERSION}-bin-${SPARK_HADOOP_VERSION}.tgz && \
    rm spark-${SPARK_VERSION}-bin-${SPARK_HADOOP_VERSION}.tgz

################
##### App ######
################

# Copy build configuration and source code
COPY build.sbt $APP_HOME/
COPY . $APP_HOME/

# Update and compile with sbt
RUN sbt update && sbt compile

# Make the entrypoint script executable and set it as the entrypoint
RUN chmod +x $APP_HOME/entrypoint.sh
ENTRYPOINT ["/home/dataintensive/entrypoint.sh"]
