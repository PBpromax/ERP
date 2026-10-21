FROM eclipse-temurin:17-jre
WORKDIR /app
COPY target/internal-oms.jar /app/internal-oms.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/internal-oms.jar"]
