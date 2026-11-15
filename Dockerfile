FROM eclipse-temurin:25-jre
WORKDIR /app

# Reutiliza o JAR gerado por ./mvnw verify, sem recompilar ou repetir testes.
COPY target/numberone-billing-service-*.jar app.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
