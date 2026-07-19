# Imagen JVM para la API objetivo (Quarkus fast-jar).
# Se construye tras 'mvn package' (usa target/quarkus-app).
FROM eclipse-temurin:21-jre

ENV LANG=C.UTF-8
WORKDIR /work

# Estructura fast-jar de Quarkus (el orden importa para el cache de capas)
COPY target/quarkus-app/lib/ /work/lib/
COPY target/quarkus-app/*.jar /work/
COPY target/quarkus-app/app/ /work/app/
COPY target/quarkus-app/quarkus/ /work/quarkus/

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/work/quarkus-run.jar"]
