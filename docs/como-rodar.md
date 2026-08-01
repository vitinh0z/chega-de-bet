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

## As duas montagens da stack

O `docker-compose.yml` é a stack **de produção**, enxuta. A observabilidade local é um
overlay somado por cima:

| | Comando | O que sobe |
|---|---|---|
| **Dev / self-hosted** | `make up` | app + postgres + alloy + Prometheus + Loki + Grafana |
| **VM (Fase 1)** | `make up-prod` | app + postgres + alloy → Grafana Cloud |

Prometheus, Loki e Grafana juntos consomem mais RAM que o próprio backend em idle, o que
anulava o ganho de caber no free tier ao lado do Postgres. Na VM fica só o **Alloy**
(~30-50 MB): ele faz o scrape das métricas e lê os logs dos contêineres, mandando tudo
por `remote_write` para o Grafana Cloud, sem armazenar nada localmente.

Rodando `docker compose up -d` na mão você recebe a stack **enxuta** — a completa exige o
overlay explícito, o que evita subir Grafana na VM sem querer:

```bash
docker compose -f docker-compose.yml -f docker-compose.observability.yml up -d
```

### Configurar o Grafana Cloud (só para a VM)

1. Em `grafana.com`, na sua stack, copie **URL + usuário** de cada sinal — eles ficam em
   páginas diferentes e os usuários são numéricos e **diferentes entre si**:
   *Send Metrics* (`.../api/prom/push`) e *Send Logs* (`.../loki/api/v1/push`).
2. Em **Access Policies**, crie uma policy com os escopos `metrics:write` e `logs:write`
   e gere um token — um só atende aos dois sinais. Ele aparece **uma única vez**.
3. Preencha o `.env` (git-ignorado):
   ```bash
   cp .env.example .env
   ```
4. Suba e confira:
   ```bash
   make up-prod
   docker compose logs -f alloy
   ```
   Na UI do Alloy (`http://localhost:12345`) todo componente deve estar *healthy*. Um
   `remote_write` com 401 quase sempre é o usuário de métricas trocado com o de logs.
   No Grafana Cloud, em Explore, `up{job="chega-de-bet-backend"}` deve dar `1` em cerca
   de um minuto, e os logs aparecem em `{container="cdb-app"}`.

> O `make up-prod` recusa subir sem as credenciais. Não é frescura: com a URL vazia o
> Alloy sobe normalmente e apenas não envia nada — a falha seria silenciosa.

## Serviços e portas

| Serviço | URL |
|---|---|
| API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Actuator / health | http://localhost:8081/actuator/health |
| Métricas Prometheus (app) | http://localhost:8081/actuator/prometheus |
| Grafana *(só `make up`)* | http://localhost:3000 (admin/admin) |
| Prometheus *(só `make up`)* | http://localhost:9090 |
| Loki *(só `make up`)* | http://localhost:3100 |
| UI do Alloy | http://localhost:12345 |
| PostgreSQL | localhost:5432 (chegadebet/chegadebet) |

Os três marcados existem apenas na montagem de desenvolvimento. Na VM, quem cumpre esse
papel é o Grafana Cloud.

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

Para como a imagem chega até a VM de produção, veja [Deploy](deploy.md).
