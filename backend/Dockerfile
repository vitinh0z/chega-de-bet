# ---- Estágio de build ----
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app

# Cacheia dependências separando o download do build do código
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Estágio de runtime ----
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# Usuário sem privilégios
RUN groupadd --system spring && useradd --system --gid spring spring

COPY --from=build /app/target/app.jar app.jar
USER spring

# NÃO defina -XX:MaxRAMPercentage aqui. É tentador (o padrão da JVM reserva só 25% para
# o heap), mas a flag só é segura junto de um limite de memória no contêiner: sem limite,
# a JVM toma como base a RAM do HOST. Medido: 75% sem limite dá 2800 MB de heap nesta
# máquina de teste — numa VM de 12 GB seriam ~9 GB, sufocando o Postgres ao lado.
#
# O lugar certo desse ajuste é o compose, junto de `mem_limit`, para os dois nunca se
# separarem. Ver docs/deploy.md. Se precisar, sobrescreva por ali:
#   environment: { JDK_JAVA_OPTIONS: "-XX:MaxRAMPercentage=75.0" }
#   mem_limit: 768m
#
# O padrão da JVM já é container-aware e mede-se suficiente para esta app: com limite de
# 768m ela subiu com 192 MB de heap e 359 MB de RSS.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
