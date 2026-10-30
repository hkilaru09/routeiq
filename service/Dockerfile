FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline
COPY src src
RUN ./mvnw -B -q -DskipTests package && cp target/routeiq-service-*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 routeiq
USER routeiq
COPY --from=build /app.jar /app/routeiq.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/routeiq.jar"]
