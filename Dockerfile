# ─────────────────────────────────────────────────────────────
# 1단계 · 빌드 스테이지
#   여기서만 JDK(컴파일러)와 Gradle을 쓴다. 결과물인 jar 하나만
#   2단계로 넘기고 이 스테이지는 통째로 버려진다.
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# 의존성 목록만 먼저 복사해서 받아둔다.
# 소스만 고쳤을 때 이 레이어가 캐시에 남아 재빌드가 빨라진다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

# 소스는 자주 바뀌므로 마지막에 복사한다.
COPY src src
RUN ./gradlew --no-daemon bootJar -x test


# ─────────────────────────────────────────────────────────────
# 2단계 · 실행 스테이지
#   JRE(실행기)만 있고 컴파일러·Gradle·소스코드가 없다.
#   이미지가 가볍고, 서버가 털려도 공격자가 쓸 도구가 없다.
# ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

# HEALTHCHECK가 쓸 curl. 이것 때문에 몇 MB 늘지만
# "떠 있음"과 "정상 동작 중"을 구분할 수 있게 된다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

COPY --from=builder /workspace/build/libs/*.jar app.jar

EXPOSE 8080

# SISC가 주석 처리해둔 그 줄. 우리는 켠다.
#   start-period : 스프링이 부팅하는 60초 동안은 실패해도 안 센다
#   interval     : 15초마다 확인
#   retries      : 5번 연속 실패하면 unhealthy 판정
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1

# JAVA_OPTS는 compose에서 주입한다. sh -c 를 거쳐야 변수가 펼쳐진다.
ENTRYPOINT ["sh", "-c", "exec java ${JAVA_OPTS} -jar /app/app.jar"]
