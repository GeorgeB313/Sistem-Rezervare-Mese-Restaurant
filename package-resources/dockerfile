# build stage
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /work
COPY pom.xml .
COPY src ./src
RUN mvn -B -DskipTests package

# runtime stage
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /work/target/rezervari-restaurant-1.0.0.jar /app/app.jar
COPY rezervare.html /app/rezervare.html
RUN mkdir -p /app/data

# Ruleaza clasa principala; daca iese (ex: headless), tine containerul in viata pentru debugging/DB
ENTRYPOINT ["/bin/sh", "-c", "java -cp /app/app.jar com.rezervari.main; tail -f /dev/null"]
