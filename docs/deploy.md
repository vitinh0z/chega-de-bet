# Deploy

Como a imagem do backend é construída e como ela chega à VM de produção.

> Estado: a plataforma de destino da Fase 1 é uma **Oracle Ampere A1** (aarch64), decidida
> na issue #127. Este documento cobre o pipeline de imagem; o provisionamento da VM em si
> ainda não está automatizado.

## A imagem é construída no CI, não na VM

O `docker compose up --build` na própria VM funciona, mas gasta exatamente os recursos que
queremos preservar: compilar o projeto puxa dependências, ocupa CPU e RAM, e deixa a
máquina lenta durante o deploy. Numa VM pequena isso é caro.

O pipeline (`.github/workflows/backend-release.yml`) resolve assim:

```
push na main  ->  runner ubuntu-24.04-arm  ->  docker build (linux/arm64)  ->  GHCR
                                                                                 |
                                                          VM só faz docker pull  <
```

**Por que um runner ARM e não emulação.** O destino é aarch64. Buildar com QEMU num runner
x86 funciona, mas é lento. Runners ARM64 hospedados são gratuitos em repositório público
desde agosto de 2025 — e este repositório é público. O label é `ubuntu-24.04-arm`.

A imagem publicada é `ghcr.io/vitinh0z/chega-de-bet/backend`, com duas tags: `latest`
(só a partir da branch padrão) e o SHA completo do commit. Prefira o SHA em produção:
`latest` é conveniente para testar, mas não diz o que está rodando nem permite voltar
atrás com precisão.

Só `linux/arm64` é publicada. Em desenvolvimento (x86) o compose constrói localmente, então
uma imagem multi-arch seria peso sem uso.

## Deploy na VM

```bash
# A imagem é pública, então não precisa de login. Se o pacote for tornado privado:
#   echo $GHCR_TOKEN | docker login ghcr.io -u <usuario> --password-stdin

docker pull ghcr.io/vitinh0z/chega-de-bet/backend:<sha>
```

> **Pendência conhecida:** o `docker-compose.yml` ainda declara `build:` para o serviço
> `app`, ou seja, continua compilando na VM. Trocar por `image:` toca o mesmo arquivo que
> o PR #162 reescreve, então essa linha entra logo depois que ele mergear, para não criar
> conflito à toa.

## Consumo de memória: o que foi medido

Antes de decidir sobre imagem nativa, medi a aplicação real em contêiner, com Postgres ao
lado e o perfil `prod` ativo:

| | Valor |
|---|---|
| Imagem (JVM) | 602 MB |
| RSS da app, limite de 768m | **359 MB** (47% do limite) |
| Heap concedido pela JVM | 192 MB (padrão: 25% do limite) |
| Postgres ao lado, ocioso | 84 MB |
| Partida | ~32 s |

**A JVM com o padrão já basta.** A app sobe e opera com 192 MB de heap; não foi preciso
nenhum ajuste para caber. Num orçamento de 12 GB (ver issue #132), app + Postgres + Alloy
somam algo na casa de 500 MB.

### O ajuste que parece óbvio e não é

É tentador colocar `-XX:MaxRAMPercentage=75` na imagem, já que 25% soa conservador. **Não
faça isso no `Dockerfile`.** Essa flag só é segura junto de um limite de memória no
contêiner — sem limite, a JVM usa a RAM do **host** como base. Medido nas três situações:

| Configuração | Heap máximo |
|---|---|
| Padrão, contêiner de 768m | 192 MB |
| `MaxRAMPercentage=75`, contêiner de 768m | 576 MB |
| `MaxRAMPercentage=75`, **sem limite** | **2800 MB** (75% da RAM do host) |

Na VM de 12 GB, a terceira linha viraria ~9 GB de heap, sufocando o Postgres. Por isso os
dois andam juntos, e no compose — não na imagem:

```yaml
app:
  mem_limit: 768m
  environment:
    JDK_JAVA_OPTIONS: "-XX:MaxRAMPercentage=75.0"
```

`JDK_JAVA_OPTIONS` em vez de flags no `ENTRYPOINT`: o launcher do `java` lê essa variável
sozinho, o que mantém o `ENTRYPOINT` em *exec form* (o processo Java é o PID 1 e recebe os
sinais de parada direto, sem shell no meio) e permite ajustar sem reconstruir a imagem.

Um atalho que **não** funciona: `-XX:MaxRAM=1g` como teto de segurança. Ele *substitui* a
detecção do contêiner em vez de limitá-la — testado, dá 768 MB de heap até num contêiner
de 512m, que seria morto por OOM. Descartado.

## Imagem nativa (GraalVM): adiada, e por quê

A issue #130 previa gerar binário nativo com GraalVM. **A decisão foi não fazer isso na
Fase 1.** O raciocínio, para quem revisitar:

**O que a nativa entregaria:** partida em ~100ms em vez dos 32s medidos, e talvez 150-200 MB
a menos de RSS.

**Por que isso não paga aqui:**

- **A partida rápida não serve a este caso.** É um servidor de vida longa, que reinicia em
  deploy. Ganhar 30 segundos algumas vezes por semana não vale nada. Partida nativa importa
  em *scale-to-zero* e serverless, que não é o modelo desta VM.
- **A economia de RAM é pequena diante do orçamento.** A medição acima mostra a app JVM em
  **359 MB**. Mesmo que a nativa cortasse metade disso, seriam ~180 MB economizados num
  orçamento de 12 GB — cerca de 1,5%. Não é o gargalo.
- **O custo é real e recorrente.** Native image exige configuração de reflection e
  recursos; este backend usa Hibernate (reflection pesada), Flyway (migrations são
  *recursos* de classpath), Spring Security e springdoc — justamente as bibliotecas que
  mais dão trabalho em AOT. Cada dependência nova vira risco de quebrar o build nativo, e
  o erro costuma aparecer só em runtime, no ambiente compilado.
- **Debug fica mais difícil** exatamente quando mais dói: sem heap dump e JFR do jeito
  habitual, e com stack traces menos diretos.
- **O build fica lento e pesado.** `native-image` consome vários GB de RAM e minutos de
  CPU. No runner ARM de 4 vCPU isso é sensível.

**Quando revisitar.** Quando a RAM realmente apertar — mais serviços na mesma VM, ou o
Postgres precisando de mais buffer — meça primeiro o consumo real da app. Se a JVM com
`MaxRAMPercentage` ajustado ainda for o gargalo, aí a nativa se justifica. Antes disso é
otimização sem problema para resolver.

**Se for feito, precisa ser em runner ARM.** `native-image` **não** faz cross-compile de
arquitetura: um binário gerado em x86 não roda na Ampere A1. Por isso o workflow já nasceu
em `ubuntu-24.04-arm` — quando a nativa entrar, ela é um job a mais no mesmo arquivo, sem
mudar a infraestrutura do pipeline.
