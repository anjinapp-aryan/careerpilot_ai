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
# Container-aware heap sizing — originally tuned for Render/Koyeb's free 512MB
# instances, where an unbounded JVM heap can OOM-kill the container. Cap heap,
# cap metaspace/stacks explicitly, and use SerialGC (far lower memory overhead
# than G1 at this heap size). Now running on an Oracle Cloud VM with 5.5GB RAM
# and no docker-compose memory limit, so MaxRAMPercentage=50 has ample room —
# but MaxMetaspaceSize=160m was carried over unchanged from the 512MB target
# and proved too tight: the backend was observed crash-looping in production
# with `OutOfMemoryError: Metaspace` after several hours of uptime (class
# metadata from the AI provider/reflection-heavy libraries accumulates over
# time). Raised to 320m, well within the VM's ~2.9GB available headroom.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:+UseContainerSupport -XX:+UseSerialGC -Xss256k -XX:MaxMetaspaceSize=320m -XX:CompressedClassSpaceSize=96m -XX:MaxDirectMemorySize=32m -XX:ReservedCodeCacheSize=64m"
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
