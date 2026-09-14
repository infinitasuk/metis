FROM maven:3.9-eclipse-temurin-21 AS build

ARG GIT_SHA=unknown

WORKDIR /workspace
COPY pom.xml .
COPY engine-core/pom.xml engine-core/pom.xml
COPY engine-api/pom.xml engine-api/pom.xml
COPY webapp/pom.xml webapp/pom.xml
COPY database/pom.xml database/pom.xml
RUN mvn -q -pl webapp -am -DskipTests dependency:go-offline
COPY engine-core ./engine-core
COPY engine-api ./engine-api
COPY webapp ./webapp
COPY database ./database
RUN mvn -q -pl webapp -am -DskipTests package

FROM eclipse-temurin:21-jre

ARG GIT_SHA=unknown
ENV METIS_GIT_SHA=${GIT_SHA}

WORKDIR /app
COPY --from=build /workspace/webapp/target/metis-web-0.1.0-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
