# One image: Spring Boot app with the plain HTML/CSS/JS frontend bundled as static resources.
# Build context is the repository root (the Maven build copies ../frontend into the jar).

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml backend/pom.xml
RUN mvn -B -q -f backend/pom.xml dependency:go-offline
COPY backend/src backend/src
COPY frontend frontend
RUN mvn -B -q -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --uid 10001 app \
    && mkdir -p /data/storage && chown -R app:app /data
WORKDIR /app
COPY --from=build /src/backend/target/hybrid-platform.jar /app/app.jar
USER app
ENV STORAGE_ROOT=/data/storage \
    PORT=8080
EXPOSE 8080
VOLUME ["/data"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
