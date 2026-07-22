# Como rodar o projeto

Este documento é o ponto de entrada para rodar o Chega de Bet localmente.

> **Nota:** hoje só o backend tem código no repositório. A extensão (cliente) e o painel de moderação (frontend) ainda estão em construção — veja o estado atual em [Arquitetura](arquitetura.md). Por enquanto, "rodar o projeto" significa rodar o backend.

## Pré-requisitos

- Docker (obrigatório).
- JDK 25 (só necessário se você quiser rodar a aplicação fora do contêiner, direto pela IDE ou Maven).

## Passo a passo

1. Clone o repositório e entre na pasta `backend`:
   ```bash
   git clone https://github.com/vitinh0z/chega-de-bet.git
   cd chega-de-bet/backend
   ```
2. Suba a stack completa (aplicação, banco e observabilidade):
   ```bash
   make up
   ```
3. Se preferir rodar a aplicação pela IDE e usar Docker só para a infraestrutura de apoio (banco, Prometheus, Loki, Grafana), use:
   ```bash
   make infra
   make run
   ```
   O comando `make run` sobe a aplicação no perfil `dev`.

## Serviços e portas

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

## Testes

Os testes de backend usam Testcontainers e exigem Docker rodando:

```bash
make test
```

## Mais detalhes

Para a stack completa, a estrutura de pastas do backend e as notas de observabilidade, veja [backend/README.md](../backend/README.md).
