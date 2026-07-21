# Backend — Chega de Bet

API de denúncia e moderação de domínios de aposta. Este diretório contém o **esqueleto** do serviço: estrutura, dependências e infra de observabilidade já configuradas, com as classes ainda **sem implementação** (marcadas com `TODO`).

## Stack

| Camada | Tecnologia | Versão |
|---|---|---|
| Linguagem / runtime | Java | 25 |
| Framework | Spring Boot | 4.1.0 |
| Persistência | Spring Data JPA + PostgreSQL | 18 |
| Migrations | Flyway | (BOM Spring Boot) |
| Mapeamento | MapStruct | 1.6.3 |
| Docs da API | springdoc-openapi (Swagger UI) | 3.0.3 |
| Métricas | Actuator + Micrometer → Prometheus | v3.13.1 |
| Logs | Log estruturado (JSON) → Grafana Alloy → Loki | 3.7.3 |
| Dashboards | Grafana | 12.3.8 |
| Testes | JUnit 5 + Testcontainers | (BOM Spring Boot) |
| Análise estática | Qodana (JVM Community) | — |

## Estrutura (MVC em camadas)

```
backend/
├── pom.xml
├── Dockerfile                 # build multi-stage (Maven → JRE 25)
├── docker-compose.yml         # app + postgres + prometheus + loki + alloy + grafana
├── qodana.yaml                # config da análise estática
├── Makefile                   # atalhos: make up / run / test ...
├── observability/             # configs de prometheus, loki, alloy e grafana
└── src/
    ├── main/java/com/chegadebet/
    │   ├── ChegaDeBetApplication.java
    │   ├── config/            # @Configuration (OpenAPI, etc.)
    │   ├── domain/
    │   │   ├── model/         # @Entity: Dominio, Denuncia, TokenEfemero
    │   │   └── enums/         # StatusDominio, CategoriaAposta
    │   ├── repository/        # JpaRepository
    │   ├── service/           # @Service (regras de negócio)
    │   ├── web/
    │   │   ├── controller/    # @RestController
    │   │   └── dto/           # records de request/response
    │   ├── mapper/            # MapStruct
    │   └── exception/         # @RestControllerAdvice
    ├── main/resources/
    │   ├── application.yml            # + application-dev.yml / application-prod.yml
    │   └── db/migration/             # migrations do Flyway
    └── test/java/com/chegadebet/     # Testcontainers + contextLoads
```

> As classes Java estão **vazias de propósito** (só a estrutura + `TODO`). O projeto compila e sobe o contexto; a lógica de negócio será preenchida pelas issues de backend do roadmap.

## Como rodar

Pré-requisitos: Docker e (para rodar fora do contêiner) JDK 25.

```bash
# Sobe tudo (app + banco + observabilidade)
make up

# Ou só a infra de apoio, rodando a app pela IDE/Maven:
make infra
make run        # perfil dev
```

## Portas

| Serviço | URL |
|---|---|
| API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Actuator / health | http://localhost:8080/actuator/health |
| Métricas Prometheus (app) | http://localhost:8080/actuator/prometheus |
| Grafana | http://localhost:3000 (admin/admin) |
| Prometheus | http://localhost:9090 |
| Loki | http://localhost:3100 |
| PostgreSQL | localhost:5432 (chegadebet/chegadebet) |

## Observabilidade

- **Métricas:** o Actuator expõe `/actuator/prometheus`; o Prometheus faz scrape e o Grafana lê via datasource já provisionado.
- **Logs:** em `prod`, a app emite log JSON estruturado; o **Grafana Alloy** lê os logs dos contêineres e envia ao **Loki** (substitui o Promtail, descontinuado). O dashboard "Chega de Bet — Visão Geral" já vem provisionado com um painel de logs.

## Nota sobre o Maven Wrapper

O `mvnw` não foi incluído (Maven não estava disponível na geração do scaffold). Gere quando quiser com `mvn wrapper:wrapper`, ou deixe a IDE cuidar disso. O Dockerfile e o CI não dependem dele.
