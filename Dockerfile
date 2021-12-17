FROM rdomloge/graalvm-21.1.0-java11:multiarch AS build

RUN apt-get update
RUN apt-get install -y git wget

RUN mkdir repo 
WORKDIR /repo
RUN git clone https://github.com/rdomloge/heliumevents-native.git 
WORKDIR /repo/heliumevents-native
RUN wget --no-check-certificate https://dlcdn.apache.org/maven/maven-3/3.8.4/binaries/apache-maven-3.8.4-bin.tar.gz
RUN tar xzf apache-maven-3.8.4-bin.tar.gz
RUN ln -s apache-maven-3.8.4/bin/mvn mvn

RUN ./mvn -Dmaven.wagon.http.ssl.insecure=true -Dpackaging=native-image package

FROM ubuntu:hirsute
WORKDIR /
COPY --from=build /repo/heliumevents-native/target/heliumevents-native .

ENTRYPOINT ["/heliumevents-native"]