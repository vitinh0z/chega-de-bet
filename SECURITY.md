# Política de Segurança

Obrigado por ajudar a manter o Chega de Bet — e as pessoas que o usam — em segurança.

## Versões suportadas

O projeto está em desenvolvimento inicial (pré-1.0). Correções de segurança são aplicadas sempre sobre a versão mais recente da branch `main`. Ainda não há releases com suporte estendido.

## Como reportar uma vulnerabilidade

**Não abra uma issue pública** para falhas de segurança: isso expõe o problema antes de existir correção. Use um destes canais, em ordem de preferência:

1. **GitHub Private Vulnerability Reporting (preferido).** Abra um relatório privado em **[Security → Report a vulnerability](https://github.com/vitinh0z/chega-de-bet/security/advisories/new)**. O relatório fica visível apenas para os mantenedores.
2. **Discord (contato informal).** Entre no [servidor da comunidade](https://discord.gg/CVT4YzymJ7) e chame um mantenedor/admin por mensagem direta para combinar um canal seguro. **Não** poste detalhes da falha em canais públicos.

Ao reportar, inclua sempre que possível:

- Descrição da falha e do impacto potencial.
- Passos para reproduzir (prova de conceito), com a versão ou o commit afetado.
- Eventuais sugestões de mitigação.

## O que esperar (SLA)

Somos um projeto voluntário; ainda assim, nos comprometemos com:

- **Confirmação de recebimento:** em até **7 dias corridos**.
- **Avaliação e plano de correção:** comunicados após a triagem inicial, com atualizações periódicas até a resolução.
- **Divulgação coordenada:** trabalhamos para corrigir e divulgar em até **90 dias** a partir do relatório. Pedimos que a falha seja mantida em sigilo até a correção ou o fim desse prazo, o que ocorrer primeiro.

## Escopo

Interessam especialmente:

- **Extensão (cliente):** execução de código, vazamento de dados de navegação, ou bypass de bloqueio que exponha a pessoa usuária.
- **API / backend:** injeção, exposição de dados, falhas de autenticação/autorização, ou abuso do fluxo de denúncia (ex.: sabotagem para bloquear sites legítimos).
- **Pipeline da blocklist:** injeção de domínios, ou quebra da assinatura da lista distribuída.

Em geral **fora de escopo:** ataques que exigem acesso físico ao dispositivo da vítima, engenharia social contra mantenedores e relatórios automatizados sem impacto demonstrável.

## Reconhecimento

Com a sua autorização, creditamos quem reporta falhas válidas nas notas da correção. O projeto **não** tem programa de recompensa (bug bounty) no momento.
