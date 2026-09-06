# syntax=docker/dockerfile:1
# Imagen de producción del Observatorio (ADR-008).
#
# Dos etapas: se compila con el wrapper del propio proyecto (regla 15: nunca un mvn global) y se
# ejecuta sobre un JRE sin herramientas de compilación. Los tests NO se ejecutan aquí porque
# dependen de Testcontainers, que necesita un Docker del que el builder no dispone: la verificación
# sigue siendo `.\mvnw.cmd verify` antes de publicar (regla 10).

FROM eclipse-temurin:21-jdk-noble AS build
WORKDIR /build

# Primero solo las coordenadas: mientras el pom no cambie, Docker reutiliza la capa de dependencias.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -ntp dependency:go-offline

# src/test/ no entra en el contexto (.dockerignore), así que se salta también su compilación.
COPY src/ src/
RUN ./mvnw -B -ntp -Dmaven.test.skip=true package

# Extracción por capas (jarmode=tools de Boot 4.1): las 118 dependencias cambian mucho menos
# que el código propio, así que cada una va a su capa de imagen.
RUN java -Djarmode=tools -jar target/observatorio-zaragoza-*.jar \
        extract --layers --launcher --destination /app

FROM eclipse-temurin:21-jre-noble AS runtime

# Todo el dominio usa Clock.systemUTC() y Europe/Madrid explícito (ZaragozaTime), así que la zona
# del contenedor no afecta a ningún dato; se fija para que el cron de purga de raw_payload y las
# marcas de los logs coincidan con la hora local de Zaragoza.
# Memoria acotada a propósito, no por porcentaje (ADR-009). Railway le presenta a la JVM el límite
# del plan —8 GB—, así que un MaxRAMPercentage la deja crecer hasta varios GB de heap: sin presión,
# la JVM no recoge basura ni devuelve memoria, y la factura se paga por RAM residente (10 $/GB/mes).
# Este servicio ingiere documentos de pocos MB y atiende un tráfico mínimo; el tope es holgado.
# ExitOnOutOfMemoryError: con una sola instancia, preferimos que reinicie a que agonice.
ENV TZ=Europe/Madrid \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-Xms128m -Xmx256m -Xss512k -XX:MaxMetaspaceSize=192m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1 -XX:ReservedCodeCacheSize=64m"

RUN useradd --system --uid 10001 --create-home observatory \
    && install -d -o observatory -g observatory /app
WORKDIR /app
USER observatory

# Una capa de imagen por capa de Boot, de la que menos cambia a la que más.
COPY --from=build --chown=observatory:observatory /app/dependencies/ ./
COPY --from=build --chown=observatory:observatory /app/spring-boot-loader/ ./
COPY --from=build --chown=observatory:observatory /app/snapshot-dependencies/ ./
COPY --from=build --chown=observatory:observatory /app/application/ ./

# Railway inyecta PORT=8080 en ejecución y exige escuchar en 0.0.0.0; el perfil prod lee ambas cosas.
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
