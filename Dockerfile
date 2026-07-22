# syntax=docker/dockerfile:1

# ---- build stage ----
FROM eclipse-temurin:26-jdk AS build
ARG GRADLE_VERSION=9.6.1
RUN apt-get update && apt-get install -y --no-install-recommends \
        unzip ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o /tmp/gradle.zip \
    && unzip -q /tmp/gradle.zip -d /opt \
    && ln -s "/opt/gradle-${GRADLE_VERSION}/bin/gradle" /usr/local/bin/gradle \
    && rm /tmp/gradle.zip
WORKDIR /build
COPY . .
RUN gradle build -x test --no-daemon
RUN cp build/libs/pet-marketplace-0.0.1-SNAPSHOT.jar /app.jar

# ---- runtime stage ----
FROM eclipse-temurin:26-jre AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app.jar app.jar
ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]