# --- build stage: compile and package the Spring Boot jar ---
FROM gradle:8.14-jdk21 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/home/gradle/.gradle \
    gradle :app:bootJar --no-daemon -x test

# --- runtime stage: slim JRE image, non-root user ---
FROM eclipse-temurin:21-jre
RUN groupadd --system tenderhub && useradd --system --gid tenderhub tenderhub
WORKDIR /app
COPY --from=build /workspace/app/build/libs/tenderhub.jar /app/tenderhub.jar
USER tenderhub
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/tenderhub.jar"]
