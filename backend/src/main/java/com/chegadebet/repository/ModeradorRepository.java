package com.chegadebet.repository;

import com.chegadebet.domain.model.Moderador;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ModeradorRepository extends JpaRepository<Moderador, UUID> {

    Optional<Moderador> findByLogin(String login);
}
