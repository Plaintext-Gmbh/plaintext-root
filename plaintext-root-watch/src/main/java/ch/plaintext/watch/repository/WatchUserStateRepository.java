/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.repository;

import ch.plaintext.watch.entity.WatchUserState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WatchUserStateRepository extends JpaRepository<WatchUserState, Long> {

    Optional<WatchUserState> findByBenutzerAndDeletedFalse(String benutzer);
}
