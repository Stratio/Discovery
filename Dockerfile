# NOTE: this Dockerfile builds Metabase from source. We recommend deploying the pre-built
# images hosted on Docker Hub https://hub.docker.com/r/metabase/metabase/ which use the
# Dockerfile located at ./bin/docker/Dockerfile

FROM java:openjdk-8-jdk-alpine

ARG VERSION

ENV JAVA_HOME=/usr/lib/jvm/default-jvm
ENV PATH /usr/local/bin:$PATH
ENV LEIN_ROOT 1

ENV FC_LANG en-US
ENV LC_CTYPE en_US.UTF-8

# install core build tools
RUN apk add --update nodejs git wget bash python make g++ java-cacerts ttf-dejavu fontconfig && \
    npm install -g yarn && \
    ln -sf "${JAVA_HOME}/bin/"* "/usr/bin/"

# fix broken cacerts
RUN rm -f /usr/lib/jvm/default-jvm/jre/lib/security/cacerts && \
    ln -s /etc/ssl/certs/java/cacerts /usr/lib/jvm/default-jvm/jre/lib/security/cacerts

# install lein
ADD https://raw.github.com/technomancy/leiningen/stable/bin/lein /usr/local/bin/lein
RUN chmod 744 /usr/local/bin/lein
RUN lein install -h

# add the application source to the image
ADD . /app/source


RUN mkdir /root/.crossdata/ && \
    mkdir /root/defaultsecrets/ && \
    mv /app/source/resources/security/* /root/defaultsecrets/.


RUN mkdir /root/kms/ && \
    mv  /app/source/resources/kms/* /root/kms/.


ENV MAVEN_VERSION="3.2.5" \
    M2_HOME=/usr/lib/mvn

RUN apk add --update wget && \
  cd /tmp && \
  wget "http://ftp.unicamp.br/pub/apache/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz" && \
  tar -zxvf "apache-maven-$MAVEN_VERSION-bin.tar.gz" && \
  mv "apache-maven-$MAVEN_VERSION" "$M2_HOME" && \
  ln -s "$M2_HOME/bin/mvn" /usr/bin/mvn

RUN mvn package -f /app/source/local-query-execution-factory/pom.xml
RUN cp /app/source/local-query-execution-factory/target/local-query-execution-factory-0.2.jar /app/source/bin/lib/local-query-execution-factory-0.2.jar
RUN mvn install:install-file -Dfile=/app/source/bin/lib/local-query-execution-factory-0.2.jar -DgroupId=com.stratio.metabase -DartifactId=local-query-execution-factory -Dversion=0.2 -Dpackaging=jar

# Tu generate local docker, comment mvn dependency:get and cp. Download jar in ./bin/lib/
# http://qa.stratio.com/repository/releases/com/stratio/jdbc/stratio-crossdata-jdbc4/2.11.1/stratio-crossdata-jdbc4-2.11.1.jar
#
RUN mvn dependency:get -DgroupId=com.stratio.jdbc -DartifactId=stratio-crossdata-jdbc4 -Dversion=2.11.1 -DremoteRepositories=http://sodio.stratio.com/repository/public/ -Dtransitive=false
RUN cp /root/.m2/repository/com/stratio/jdbc/stratio-crossdata-jdbc4/2.11.1/stratio-crossdata-jdbc4-2.11.1.jar /app/source/bin/lib/stratio-crossdata-jdbc4-2.11.1.jar
RUN mvn install:install-file -Dfile=/app/source/bin/lib/stratio-crossdata-jdbc4-2.11.1.jar -DgroupId=com.stratio.jdbc -DartifactId=stratio-crossdata-jdbc4 -Dversion=2.11.1 -Dpackaging=jar

# build the app
WORKDIR /app/source

RUN bin/build

# remove unnecessary packages & tidy up
RUN apk del nodejs git wget python make g++
RUN rm -rf /root/.lein /root/.m2 /root/.node-gyp /root/.npm /root/.yarn /root/.yarn-cache /tmp/* /var/cache/apk/* /app/source/node_modules

# expose our default runtime port
EXPOSE 3000

RUN apk add --update openssl
RUN apk add --update curl

RUN apk add --update ca-certificates
RUN mkdir -p /etc/pki/tls/certs && \
    ln -s /etc/ssl/certs/ca-certificates.crt /etc/pki/tls/certs/ca-bundle.crt

# build and then run it
WORKDIR /app/source
ENTRYPOINT ["./bin/start"]
