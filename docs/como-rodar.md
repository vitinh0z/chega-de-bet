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
| Actuator / health | http://localhost:8081/actuator/health |
| Métricas Prometheus (app) | http://localhost:8081/actuator/prometheus |
| Grafana | http://localhost:3000 (admin/admin) |
| Prometheus | http://localhost:9090 |
| Loki | http://localhost:3100 |
| PostgreSQL | localhost:5432 (chegadebet/chegadebet) |

O Actuator fica na porta **8081** (`management.server.port`), que o `docker-compose`
**não publica**: na stack completa ele só é alcançável de dentro da rede interna, pelo
Prometheus. Rodando pela IDE com `make run`, a 8081 abre no seu localhost normalmente.

## Primeiro moderador

`/api/moderacao/**` exige autenticação (HTTP Basic, papel `MODERADOR`). Não existe conta
embutida: uma senha versionada neste repositório seria uma senha pública. A primeira
conta nasce de variáveis de ambiente, e só quando a tabela `moderador` está vazia:

```bash
export CHEGADEBET_MODERACAO_BOOTSTRAP_LOGIN=ana
export CHEGADEBET_MODERACAO_BOOTSTRAP_SENHA='senha longa de verdade'   # mínimo 12 caracteres
make run
```

Sem essas variáveis a aplicação sobe normalmente, mas avisa no log que a moderação vai
recusar todo mundo com 401. Depois de criar a conta, remova as variáveis do ambiente.

```bash
curl -u ana:'senha longa de verdade' http://localhost:8080/api/moderacao/fila
```

## Testes

Os testes de backend usam Testcontainers e exigem Docker rodando:

```bash
make test
```

## Mais detalhes

Para a stack completa, a estrutura de pastas do backend e as notas de observabilidade, veja [backend/README.md](../backend/README.md).
