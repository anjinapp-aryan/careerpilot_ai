FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY --from=build /workspace/target/*.jar app.jar
EXPOSE 8080
# Container-aware heap sizing — important on Render/Koyeb's free 512MB instances
# where an unbounded JVM heap can OOM-kill the container. 75% of 512MB (~384MB)
# left too little room for metaspace + thread stacks + native/off-heap memory
# (Kafka producer buffers, Netty, Postgres driver) — the process was observed
# exceeding 512MB total RSS. Cap heap lower, cap metaspace/stacks explicitly,
# and use SerialGC (far lower memory overhead than G1 at this heap size).
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:+UseContainerSupport -XX:+UseSerialGC -Xss256k -XX:MaxMetaspaceSize=160m -XX:CompressedClassSpaceSize=48m -XX:MaxDirectMemorySize=32m -XX:ReservedCodeCacheSize=64m"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
