# Chega de Bet

Extensão de navegador, open source e focada em privacidade, que bloqueia anúncios e domínios de apostas (casas de bet, cassino online e slots do tipo "tigrinho"). A lista de bloqueio é mantida de forma colaborativa e moderada, e o projeto é financiado por doação — sem anúncios e sem rastreamento.

**Status:** em desenvolvimento inicial. O esqueleto do backend (Spring Boot) já está no repositório; a extensão está em construção.

---

## O problema

O Brasil se tornou um dos maiores alvos de publicidade de apostas do mundo. Após a regulamentação do setor (Lei 14.790/2023, em vigor desde 2025), as casas de aposta passaram a anunciar em escala industrial. O resultado é conhecido: superendividamento, publicidade agressiva dirigida a quem já está vulnerável e a normalização de jogos de azar disfarçados de entretenimento.

Bloquear anúncio de aposta não é censurar algo ilegal — é higiene de exposição. É o mesmo princípio de qualquer bloqueador de anúncios, aplicado a um segmento específico e comprovadamente nocivo.

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
2. **Transparência.** A lista de bloqueio e os critérios de inclusão são públicos e auditáveis.
3. **Sem anúncios.** Um bloqueador de anúncios que exibe anúncios não merece confiança.
4. **Falso positivo é falha crítica.** Bloquear um site legítimo, jornalístico ou de apoio a dependentes é a pior falha possível, e é tratada como tal.

## Como funciona, em linhas gerais

A extensão carrega uma lista de bloqueio assinada e impede que o navegador acesse os domínios listados, antes mesmo da requisição ser feita. A lista tem três fontes de alimentação: uma base curada por mantenedores, denúncias da comunidade que passam por moderação, e sinais auxiliares que apenas ajudam a priorizar a fila de revisão. Quando o usuário clica em um link direto de aposta, uma página de aviso explica o bloqueio e oferece informação de apoio a jogo compulsivo.

## Documentação

- [Visão geral da ideia](docs/visao-geral.md) — o ciclo do projeto e os pilares, em diagramas.
- [Arquitetura (backend, frontend e cliente)](docs/arquitetura.md) — a visão técnica de ponta a ponta.

## Como apoiar

O projeto tem custos (infraestrutura e taxas das lojas de extensão) e nenhuma fonte de receita além de doação. As formas de apoio serão divulgadas conforme o desenvolvimento avança:

- Pix
- Apoia-se
- GitHub Sponsors

## Contribuir

Contribuições são bem-vindas: denúncias de domínios, revisão da lista, código e tradução. As diretrizes de contribuição serão publicadas junto com a primeira versão.

## Licença

A definir. A intenção é uma licença copyleft (provavelmente GPL-3.0), para manter o projeto e suas listas sempre abertos.
