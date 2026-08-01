# Como contribuir

Obrigado por querer ajudar o Chega de Bet. Existem quatro formas de contribuir: denúncia de domínios, revisão da blocklist, código e tradução/documentação.

## Denúncia de domínios

O canal de denúncia pela extensão ainda não está disponível — a extensão está em construção. Por enquanto, para sugerir um domínio de aposta que deveria entrar na blocklist, abra uma [issue](https://github.com/vitinh0z/chega-de-bet/issues) com:

- O domínio completo.
- Uma evidência de que é um domínio de apostas (link de anúncio, print de tela, ou outra fonte pública).

Toda denúncia passa por moderação humana antes de virar bloqueio. Veja o fluxo completo em [docs/visao-geral.md](docs/visao-geral.md).

## Revisão da blocklist

A blocklist ainda não existe como artefato público — ela nasce junto com o backend e o pipeline descritos em [docs/arquitetura.md](docs/arquitetura.md). Quando estiver disponível, este documento vai ganhar instruções específicas de revisão.

## Código

1. Faça um fork do repositório e clone o seu fork.
2. Crie uma branch a partir de `develop`.
3. Se for mexer no backend, siga [docs/como-rodar.md](docs/como-rodar.md) para subir o ambiente local.
4. Rode os testes antes de abrir o PR:
   ```bash
   cd backend
   make test
   ```
5. Siga o estilo de código do arquivo `.editorconfig` já configurado em `backend/`.
6. Abra um PR com uma descrição clara do que mudou e por quê. Mantenha o PR pequeno e focado em uma única mudança.

> **Nota:** todo PR passa pela CI (`backend-ci.yml`, que roda `mvn clean verify`) e por análise estática do Qodana. As duas verificações precisam passar antes do merge.

## Tradução e documentação

Toda a documentação do projeto é em português e segue o [guia de escrita](docs/guia-de-escrita.md). Antes de editar ou criar um documento, leia o guia — ele define frases curtas, voz ativa e um glossário fixo de termos do projeto.

## Dúvidas

Se não tiver certeza sobre uma mudança antes de investir tempo nela, abra uma issue descrevendo o que você quer fazer. É melhor alinhar antes do que refazer um PR inteiro depois.
