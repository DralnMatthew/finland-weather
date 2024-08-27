FROM ubuntu:20.04

# Environment variable for application home
ENV APP_HOME=/home/dataintensive
ENV JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64

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

################
##### App ######
################

# Copy build configuration and source code
COPY build.sbt $APP_HOME/
COPY . $APP_HOME/

# Build and compile the application
RUN sbt update && \
    sbt compile

# Make the entrypoint script executable and set it as the entrypoint
RUN chmod +x $APP_HOME/entrypoint.sh
ENTRYPOINT ["/home/dataintensive/entrypoint.sh"]
