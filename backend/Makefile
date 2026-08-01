# Atalhos de desenvolvimento do backend Chega de Bet
# Uso: make <alvo>

.DEFAULT_GOAL := help

# A stack de desenvolvimento é o compose base MAIS o overlay de observabilidade local.
# Sozinho, o base é a stack enxuta da VM (app + postgres + alloy -> Grafana Cloud).
COMPOSE_DEV := docker compose -f docker-compose.yml -f docker-compose.observability.yml

.PHONY: help
help: ## Lista os alvos disponíveis
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

.PHONY: build
build: ## Compila e empacota (pula testes)
	mvn -B clean package -DskipTests

.PHONY: test
test: ## Roda os testes (usa Testcontainers, precisa de Docker)
	mvn -B test

.PHONY: run
run: ## Sobe a aplicação localmente (perfil dev)
	mvn spring-boot:run -Dspring-boot.run.profiles=dev

.PHONY: up
up: ## Sobe a stack completa de desenvolvimento (app + banco + observabilidade local)
	$(COMPOSE_DEV) up -d --build

.PHONY: up-prod
up-prod: ## Sobe a stack enxuta da VM (app + banco + alloy -> Grafana Cloud)
	@test -f .env || { \
		echo "Falta o arquivo .env com as credenciais do Grafana Cloud."; \
		echo "Copie o modelo:  cp .env.example .env"; \
		exit 1; \
	}
	@grep -qE '^GRAFANA_CLOUD_PROM_URL=.+' .env || { \
		echo "GRAFANA_CLOUD_PROM_URL está vazio no .env."; \
		echo "Sem isso o Alloy sobe normalmente e não envia nada — a falha é silenciosa."; \
		exit 1; \
	}
	docker compose up -d --build

.PHONY: down
down: ## Derruba a stack (completa ou enxuta)
	$(COMPOSE_DEV) down

.PHONY: infra
infra: ## Sobe só a infra de apoio local (banco + observabilidade), sem a app
	$(COMPOSE_DEV) up -d postgres prometheus loki alloy grafana

.PHONY: logs
logs: ## Acompanha os logs dos contêineres
	$(COMPOSE_DEV) logs -f

.PHONY: clean
clean: ## Limpa artefatos de build
	mvn clean
