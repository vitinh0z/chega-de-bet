# Atalhos de desenvolvimento do backend Chega de Bet
# Uso: make <alvo>

.DEFAULT_GOAL := help

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
up: ## Sobe toda a stack (app + banco + observabilidade)
	docker compose up -d --build

.PHONY: down
down: ## Derruba a stack
	docker compose down

.PHONY: infra
infra: ## Sobe só a infra de apoio (banco + observabilidade), sem a app
	docker compose up -d postgres prometheus loki alloy grafana

.PHONY: logs
logs: ## Acompanha os logs dos contêineres
	docker compose logs -f

.PHONY: clean
clean: ## Limpa artefatos de build
	mvn clean
