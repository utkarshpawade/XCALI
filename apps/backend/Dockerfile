# Build context is this directory:
#   docker build -t excalidraw-backend apps/backend
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
# Dependencies first, so a code change reuses the cached layer.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package && cp target/backend-*.jar app.jar

FROM eclipse-temurin:21-jre-alpine AS runner
WORKDIR /app
RUN addgroup -S app && adduser -S -G app app
COPY --from=builder /build/app.jar app.jar
USER app

# The prod profile refuses to start without a real JWT_SECRET.
ENV SPRING_PROFILES_ACTIVE=prod
# Sized for small hosts (Render free: 512 MB). Override per host if needed.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"
EXPOSE 3001
# exec form: java is PID 1 and receives SIGTERM for graceful shutdown.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
