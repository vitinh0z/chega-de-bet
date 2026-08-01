# Backend — Chega de Bet

API de denúncia e moderação de domínios de aposta. Este diretório contém o **esqueleto** do serviço: estrutura, dependências e infra de observabilidade já configuradas, com as classes ainda **sem implementação** (marcadas com `TODO`).

> Procurando o passo a passo para rodar o projeto pela primeira vez? Veja [docs/como-rodar.md](../docs/como-rodar.md). Procurando como a imagem chega até a VM de produção? Veja [docs/deploy.md](../docs/deploy.md).

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
| Actuator / health | http://localhost:8081/actuator/health |
| Métricas Prometheus (app) | http://localhost:8081/actuator/prometheus |
| Grafana | http://localhost:3000 (admin/admin) |
| Prometheus | http://localhost:9090 |
| Loki | http://localhost:3100 |
| PostgreSQL | localhost:5432 (chegadebet/chegadebet) |

## Observabilidade

A observabilidade tem **duas montagens**, e o compose base é a de produção:

| | Comando | O que sobe |
|---|---|---|
| **VM (Fase 1)** | `make up-prod` | app + postgres + **alloy** → Grafana Cloud |
| **Dev / self-hosted** | `make up` | o de cima **+** Prometheus, Loki e Grafana locais |

Prometheus, Loki e Grafana juntos consomem mais RAM que o próprio backend em idle, o que
anulava o ganho de caber no free tier ao lado do Postgres. Na VM fica só o **Alloy**
(~30-50 MB), que faz scrape das métricas e lê os logs dos contêineres, mandando tudo por
`remote_write` para o Grafana Cloud — nada é armazenado na VM.

O que separa as duas montagens é qual arquivo o Alloy monta:
`observability/alloy/config.alloy` (nuvem) ou `observability/alloy/config.local.alloy`
(Loki local). O overlay `docker-compose.observability.yml` troca um pelo outro.

Para a VM, copie `.env.example` para `.env` e preencha as credenciais do Grafana Cloud.
`make up-prod` recusa subir sem elas: o Alloy sobe normalmente com a URL vazia e
simplesmente não envia nada, então a falha seria silenciosa.

- **Métricas:** o Actuator expõe `/actuator/prometheus` na porta de gestão **8081**, que o `docker-compose` não publica — as métricas não ficam abertas na internet. Quem faz o scrape é o Alloy (na VM) ou o Prometheus local (em dev), sempre por dentro da rede (`app:8081`).
- **Logs:** em `prod`, a app emite log JSON estruturado; o **Grafana Alloy** lê os logs dos contêineres e envia ao **Loki** (substitui o Promtail, descontinuado). O dashboard "Chega de Bet — Visão Geral" já vem provisionado com um painel de logs.

## Nota sobre o Maven Wrapper

O `mvnw` não foi incluído (Maven não estava disponível na geração do scaffold). Gere quando quiser com `mvn wrapper:wrapper`, ou deixe a IDE cuidar disso. O Dockerfile e o CI não dependem dele.
