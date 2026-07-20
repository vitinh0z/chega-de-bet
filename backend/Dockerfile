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

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
