package com.chegadebet.service;

import com.chegadebet.domain.model.Dominio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementação provisória: registra em log que o domínio está pronto para entrar na
 * blocklist. Serve para acompanhar o fluxo enquanto o pipeline Git não existe.
 * <p>
 * Logar o host aqui não é dado pessoal: é o site denunciado, que a blocklist publica
 * aberta de qualquer forma.
 */
@Component
public class LogBlocklistPublisher implements BlocklistPublisher {

    private static final Logger log = LoggerFactory.getLogger(LogBlocklistPublisher.class);

    @Override
    public void publicar(Dominio dominio) {
        log.info("Domínio aprovado e pronto para a blocklist: {}", dominio.getHost());
    }
}
