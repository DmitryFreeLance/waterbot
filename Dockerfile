FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -q -B dependency:go-offline

COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

COPY --from=build /app/target/waterbot-jar-with-dependencies.jar app.jar

VOLUME ["/data", "/app/media"]

CMD ["java", "-jar", "app.jar"]