# Service A as an image: the Maven build in one stage, the plain JRE in the other. Build context: estate/services.
#   docker build -f service-a/Dockerfile -t estate/service-a:local services
# (The runtime's own service containers use the hotswap image and a mounted jar; this image is what the deploy pipeline ships.)
FROM docker.io/library/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -q -B -DskipTests -pl service-a -am package

FROM docker.io/library/eclipse-temurin:21-jre
COPY --from=build /src/service-a/target/service-a-0.1.0.jar /app/service-a.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/service-a.jar"]
