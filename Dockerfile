FROM eclipse-temurin:25-jre
WORKDIR /app
ENV DD_HTTP_SERVER_TAG_QUERY_STRING=false DD_HTTP_CLIENT_TAG_QUERY_STRING=false
ADD --chmod=0444 --checksum=sha256:bdd2f818138b683d0c266637fbda4bccf01e940d55bdeb2ea43aaa6d43218ddf https://repo.maven.apache.org/maven2/com/datadoghq/dd-java-agent/1.64.2/dd-java-agent-1.64.2.jar /app/dd-java-agent.jar

# Reutiliza o JAR gerado por ./mvnw verify, sem recompilar ou repetir testes.
COPY target/numberone-billing-service-*.jar app.jar

COPY --chmod=755 entrypoint.sh /app/entrypoint.sh
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
