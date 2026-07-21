# Visão geral da ideia

Este documento mostra, em alto nível, **como o Chega de Bet funciona como ideia** — o ciclo entre quem usa, a comunidade que denuncia e a lista que bloqueia. Os detalhes internos de implementação ficam na documentação técnica do projeto.

## O ciclo do projeto

A extensão bloqueia localmente com base numa lista; a comunidade alimenta essa lista por denúncia; a moderação humana decide o que entra; e doações pagam a infraestrutura que distribui a lista de volta para todo mundo.

```mermaid
flowchart TD
    subgraph Navegador["No navegador da pessoa"]
        U["Pessoa navegando"] --> EXT["Extensão Chega de Bet"]
        EXT --> DEC{"É domínio de aposta?"}
        DEC -->|"sim"| BLOCK["Bloqueia e mostra tela<br/>com mensagem de ajuda"]
        DEC -->|"não"| PASS["Navega normalmente"]
    end

    EXT -->|"consulta"| BL[("Blocklist assinada")]
    U -->|"denuncia um site"| CANAL["Canal de denúncia"]

    subgraph Curadoria["Curadoria colaborativa"]
        CANAL --> Q["Fila de moderação<br/>(quarentena)"]
        Q --> MOD["Revisão humana"]
        MOD -->|"aprovado"| GIT[("Lista no Git<br/>fonte da verdade")]
        MOD -->|"rejeitado"| DESCARTE["Descartado"]
    end

    GIT --> CDN[("Publicação em CDN")]
    CDN -->|"atualiza"| BL
    DOA["Apoios e doações"] -->|"financiam a infra"| CDN
```

## Da denúncia ao bloqueio

Ninguém é bloqueado só porque uma pessoa denunciou: toda denúncia passa por quarentena e revisão humana antes de virar bloqueio para a comunidade inteira.

```mermaid
sequenceDiagram
    actor U as Pessoa
    participant E as Extensão
    participant M as Moderação
    participant L as Blocklist

    U->>E: "Denunciar este site"
    E->>M: envia o domínio (anônimo)
    Note over M: entra em quarentena<br/>e é revisado por um humano
    M->>L: aprovado, adiciona à lista
    L-->>E: chega na próxima atualização
    Note over U,E: agora o domínio é bloqueado<br/>para toda a comunidade
```

## Os pilares

```mermaid
mindmap
  root(("Chega de Bet"))
    Privacidade
      Bloqueio local
      Sem rastreamento
      Denúncia anônima
    Transparência
      Lista pública
      Critério objetivo
      Moderação auditável
    Comunidade
      Denúncia colaborativa
      Moderação humana
      Anti-sabotagem
    Sustentabilidade
      Doações
      Custo baixo
      Sem anúncios
```

---

> Cobertura nunca será 100% — o objetivo é **reduzir a exposição** a anúncios e domínios de aposta, não prometer bloqueio perfeito.
