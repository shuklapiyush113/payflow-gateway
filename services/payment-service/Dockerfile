# Build from the repository root:  docker build -f services/payment-service/Dockerfile .
# Multi-arch (the Oracle free VM is ARM64):  docker buildx build --platform linux/amd64,linux/arm64 ...

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY services/payment-service/pom.xml services/payment-service/pom.xml
RUN ./mvnw -q -pl services/payment-service -am dependency:go-offline
COPY services/payment-service/src services/payment-service/src
RUN ./mvnw -q -pl services/payment-service -am -DskipTests package \
 && java -Djarmode=tools -jar services/payment-service/target/payment-service-*.jar extract --layers --launcher --destination /layers

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 payflow
WORKDIR /app
# Dependencies change least often, so they get the lowest (most cached) layer.
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER 10001
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
