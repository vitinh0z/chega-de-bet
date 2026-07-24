# Guia de escrita da documentação

Este guia define como escrevemos a documentação do Chega de Bet. O objetivo é simples: qualquer pessoa deve entender o texto na primeira leitura, mesmo sem experiência técnica. Quem denuncia um domínio, quem quer entender o projeto, e quem vai programar — todos leem os mesmos documentos.

## Por que seguimos o ASD-STE100

O [ASD-STE100](https://www.asd-ste100.org/) (Simplified Technical English) é um padrão internacional de escrita técnica. Ele nasceu na indústria aeroespacial europeia, nos anos 1980, para resolver um problema real: manuais de manutenção confusos causavam erros de mecânicos que liam em um segundo idioma. Hoje o padrão é usado em defesa, ferroviário, automotivo e outros setores de segurança crítica.

O padrão tem duas partes: 53 regras de escrita e um dicionário de cerca de 900 palavras aprovadas, cada uma com um único significado. As regras impõem frases curtas, voz ativa e instruções diretas.

O ASD-STE100 é uma língua controlada do **inglês**. O dicionário de palavras aprovadas não existe em português, e as regras de gramática são específicas do inglês. Por isso, não traduzimos o padrão — adaptamos os seus **princípios de clareza** para o português técnico deste projeto.

## As regras que seguimos

### 1. Frases curtas

Textos descritivos: até 25 palavras por frase. Instruções passo a passo: até 20 palavras.

- **Evite:** "A extensão verifica o domínio antes de carregar a página e, caso ele esteja na lista de bloqueio assinada, interrompe a requisição e mostra uma tela de aviso para a pessoa que estava navegando."
- **Prefira:** "A extensão verifica o domínio antes de carregar a página. Se o domínio estiver na blocklist, ela bloqueia a requisição e mostra uma tela de aviso."

### 2. Um parágrafo, um tópico

Cada parágrafo trata de uma única ideia. Se a ideia muda, o parágrafo muda.

### 3. Voz ativa e imperativo direto

Instruções usam o imperativo. Não usamos "deveria", "poderia" ou "é recomendado que se faça".

- **Evite:** "O Docker deveria estar instalado antes de rodar o comando."
- **Prefira:** "Instale o Docker antes de rodar o comando."

### 4. Um termo, um significado

Cada conceito do projeto tem uma única palavra. Não alternamos sinônimos para a mesma coisa — isso confunde quem está aprendendo o domínio do projeto. Veja o [glossário](#glossário-do-projeto) abaixo.

- **Evite:** alternar entre "denúncia" e "reporte" para a mesma ação.
- **Prefira:** usar sempre "denúncia".

### 5. Condição antes do comando

Em instruções, a condição vem primeiro, o comando depois.

- **Prefira:** "Se a porta 8080 já estiver em uso, pare o outro processo antes de rodar `make up`."

### 6. Listas verticais para sequências

Passos a seguir viram lista numerada, não um parágrafo corrido com várias instruções misturadas.

### 7. Avisos padronizados

Usamos dois formatos, só quando há risco real de erro ou perda de tempo:

> **Nota:** informação adicional útil, sem risco associado.

> **Atenção:** risco real — algo que pode falhar, quebrar ou custar tempo se ignorado.

### 8. Sem referências soltas

Evite "isso", "aquilo" ou "essa parte" sem um substantivo claro por perto. Repita o termo se precisar.

## Glossário do projeto

| Termo | Significado |
|---|---|
| Denúncia | Um envio, feito por uma pessoa, indicando que um domínio parece ser de apostas. |
| Domínio | O endereço (ex.: `exemplo.com`) avaliado para entrar ou não na blocklist. |
| Blocklist | A lista assinada de domínios bloqueados, distribuída para a extensão. |
| Moderação | A revisão humana que decide se uma denúncia vira bloqueio. |
| Quarentena | O estado de uma denúncia entre o envio e a decisão da moderação. |
| Falso positivo | Um domínio legítimo bloqueado por engano. É tratado como falha crítica. |
| Cliente | A extensão de navegador instalada por quem usa o Chega de Bet. |
| Backend | A API e o banco de dados que processam denúncias e geram a blocklist. |
| Frontend | As aplicações web do painel de moderação e do dashboard público (Fase 2). |

## Onde este guia se aplica

Todo o Markdown deste repositório segue este guia: `README.md`, `CONTRIBUTING.md`, e os documentos em `docs/`. Comentários de código e commits não precisam seguir estas regras à risca, mas se beneficiam do mesmo espírito: claro, direto, sem ambiguidade.
