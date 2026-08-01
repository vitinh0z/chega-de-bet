# Chega de Bet

[![Discord](https://img.shields.io/badge/Discord-entrar-5865F2?logo=discord&logoColor=white)](https://discord.gg/CVT4YzymJ7)

Chega de Bet é uma extensão de navegador que bloqueia anúncios e domínios de apostas: casas de bet, cassino online e slots do tipo "tigrinho". O projeto é open source, focado em privacidade e financiado só por doação. Não exibe anúncios e não rastreia quem usa.

A lista de domínios bloqueados (a blocklist) não é feita por uma pessoa só. Qualquer um pode denunciar um domínio suspeito. Uma pessoa moderadora revisa cada denúncia antes de qualquer bloqueio entrar em vigor.

**Status:** em desenvolvimento inicial. O backend (Spring Boot) já processa denúncia, moderação e autenticação. Falta publicar a blocklist de verdade — hoje isso é um placeholder. A extensão (cliente) e o painel de moderação (frontend) ainda estão em construção. Veja o estado atual de cada parte em [Arquitetura](docs/arquitetura.md).

---

## O problema

O Brasil é hoje um dos maiores alvos de publicidade de apostas do mundo. A Lei 14.790/2023, em vigor desde 2025, regulamentou o setor de apostas de quota fixa. Na prática, isso abriu caminho para as casas de aposta anunciarem em escala industrial: TV, redes sociais, patrocínio de times de futebol e influenciadores.

O resultado já é medido por pesquisas e reportagens: superendividamento, publicidade agressiva dirigida a quem já está em situação vulnerável, e a normalização de jogos de azar disfarçados de entretenimento — os jogos de "tigrinho" e similares.

Bloquear anúncio de aposta não é censurar algo ilegal. É higiene de exposição. É o mesmo princípio de qualquer bloqueador de anúncios, aplicado a um segmento específico e com dano já comprovado.

## O que o projeto é

- Uma extensão de navegador para desktop (Chrome e Firefox).
- Um bloqueador baseado em uma lista de domínios mantida publicamente.
- Um canal de denúncia colaborativa, com moderação humana antes de qualquer bloqueio.
- Um projeto financiado exclusivamente por doação.

## O que o projeto não é

- Não é um coletor de dados de navegação.
- Não exibe anúncios de espécie alguma.
- Não bloqueia sites automaticamente só porque alguém denunciou — toda denúncia passa por moderação.
- Não emite juízo sobre marcas específicas; trabalha com um critério objetivo e verificável de categoria.

## Princípios

1. **Privacidade primeiro.** O bloqueio acontece localmente, no navegador. Nada da sua navegação sai do dispositivo sem uma ação explícita sua.
2. **Transparência.** A blocklist e os critérios de inclusão são públicos e auditáveis.
3. **Sem anúncios.** Um bloqueador de anúncios que exibe anúncios não merece confiança.
4. **Falso positivo é falha crítica.** Bloquear um site legítimo, jornalístico ou de apoio a dependentes é a pior falha possível, e é tratada como tal.

## Como funciona, em linhas gerais

A extensão carrega uma blocklist assinada. Ela bloqueia o acesso a um domínio antes mesmo da requisição sair do navegador. Nenhum dado de navegação sai do dispositivo para isso.

A blocklist tem três fontes: uma base curada pelos mantenedores do projeto, denúncias da comunidade (sempre com moderação humana antes de virar bloqueio), e sinais auxiliares que só ajudam a priorizar a fila de revisão — nunca bloqueiam um domínio sozinhos.

Quando alguém tenta acessar um domínio bloqueado, a extensão mostra uma tela de aviso. Essa tela explica o motivo do bloqueio e traz informação de apoio a jogo compulsivo.

## Documentação

- [Guia de escrita](docs/guia-de-escrita.md) — o padrão de escrita usado em toda a documentação, inspirado no ASD-STE100.
- [Visão geral da ideia](docs/visao-geral.md) — o ciclo do projeto e os pilares, em diagramas.
- [Arquitetura](docs/arquitetura.md) — a visão técnica de ponta a ponta (backend, frontend e cliente).
- [Como rodar o projeto](docs/como-rodar.md) — passo a passo para subir o ambiente localmente.
- [Deploy](docs/deploy.md) — como a imagem do backend é construída e como ela chega à VM de produção.
- [Como contribuir](CONTRIBUTING.md) — denúncia de domínios, código, revisão e tradução.

## Comunidade

O desenvolvimento acontece à vista de todos. Entre no Discord para acompanhar o projeto, tirar dúvidas, sugerir melhorias e ajudar na discussão de domínios:

- **Discord:** https://discord.gg/CVT4YzymJ7

As denúncias formais continuam sendo feitas pela extensão, de forma anônima e com moderação humana — o Discord é o espaço de conversa da comunidade, não um canal de coleta de dados.

## Como apoiar

O projeto tem custos (infraestrutura e taxas das lojas de extensão) e nenhuma fonte de receita além de doação. As formas de apoio serão divulgadas conforme o desenvolvimento avança:

- Pix
- Apoia-se
- GitHub Sponsors

## Contribuir

Contribuições são bem-vindas: denúncias de domínios, revisão da lista, código e tradução. O passo a passo está em [CONTRIBUTING.md](CONTRIBUTING.md).

## Licença

Este projeto usa a licença [GPL-3.0](LICENSE). É uma licença copyleft: qualquer distribuição do código, ou de trabalhos derivados dele, precisa continuar sob a mesma licença e com o código-fonte aberto. Isso mantém o projeto e suas listas sempre abertos.
