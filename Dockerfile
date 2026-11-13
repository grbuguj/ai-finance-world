# ── 빌드 ──────────────────────────────────────────────
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
# 의존성 레이어를 소스와 분리해 캐시 (소스만 바뀌면 의존성 다운로드를 다시 하지 않음)
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

# ── 실행 ──────────────────────────────────────────────
FROM eclipse-temurin:17-jre
ENV TZ=Asia/Seoul \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /src/build/libs/*-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
