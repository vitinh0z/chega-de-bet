package com.chegadebet.service;

import com.chegadebet.config.ModeracaoProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * Decide se um host está sob a allowlist de proteção.
 * <p>
 * Existe como componente próprio porque a resposta é usada por dois caminhos que não se
 * conhecem — a moderação, que exige quórum maior para esses domínios, e a pré-análise,
 * que nem abre conexão para eles. Duas implementações da mesma regra divergiriam
 * exatamente no ponto sutil descrito em {@link #protege}, e a divergência apareceria como
 * um site de jornalismo raspado por engano, não como um erro.
 */
@Component
public class ProtecaoAllowlist {

    private final List<String> entradas;

    public ProtecaoAllowlist(ModeracaoProperties properties) {
        // Normalizado uma vez na construção: a lista não muda em tempo de execução, e
        // repetir o toLowerCase a cada host da fila é trabalho jogado fora.
        this.entradas = properties.allowlist().stream()
                .map(entrada -> entrada.toLowerCase(Locale.ROOT))
                .toList();
    }

    /**
     * Se bloquear este host por engano causaria dano grave: jornalismo, saúde, apoio a
     * dependente químico, órgão público.
     * <p>
     * Casa o host exato e os subdomínios. O ponto antes da entrada não é detalhe: com
     * {@code endsWith(entrada)} puro, {@code malgov.br} casaria com {@code gov.br} e
     * ganharia uma proteção que não é dele.
     */
    public boolean protege(String host) {
        String normalizado = host.toLowerCase(Locale.ROOT);
        return entradas.stream()
                .anyMatch(entrada -> normalizado.equals(entrada) || normalizado.endsWith("." + entrada));
    }
}
