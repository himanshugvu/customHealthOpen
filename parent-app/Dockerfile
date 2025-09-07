# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Copy poms first for better caching
COPY pom.xml ./
COPY app-health-monitor/pom.xml app-health-monitor/pom.xml
COPY parent-app/pom.xml parent-app/pom.xml

# Copy sources
COPY app-health-monitor/src app-health-monitor/src
COPY parent-app/src parent-app/src

RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/parent-app/target/parent-app-0.1.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8089
ENTRYPOINT ["java","-jar","/app/app.jar"]
