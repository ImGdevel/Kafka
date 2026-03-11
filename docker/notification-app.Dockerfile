FROM eclipse-temurin:21-jdk AS build

ARG MODULE_NAME

WORKDIR /workspace

COPY gradlew gradlew
COPY gradlew.bat gradlew.bat
COPY settings.gradle build.gradle ./
COPY gradle gradle
COPY kafka-notification kafka-notification

RUN chmod +x gradlew \
	&& ./gradlew ":kafka-notification:${MODULE_NAME}:bootJar" --no-daemon

FROM eclipse-temurin:21-jre

ARG MODULE_NAME

WORKDIR /app

COPY --from=build /workspace/kafka-notification/${MODULE_NAME}/build/libs/${MODULE_NAME}-0.0.1-SNAPSHOT.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
