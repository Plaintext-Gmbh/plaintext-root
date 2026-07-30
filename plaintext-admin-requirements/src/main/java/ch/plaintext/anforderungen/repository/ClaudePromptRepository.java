/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.repository;

import ch.plaintext.anforderungen.entity.ClaudePrompt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ClaudePrompt entity.
 */
public interface ClaudePromptRepository extends JpaRepository<ClaudePrompt, Long> {

    Optional<ClaudePrompt> findByPromptNumber(String promptNumber);

    List<ClaudePrompt> findByAnforderungIdOrderByCreatedDateDesc(Long anforderungId);

    List<ClaudePrompt> findByStatusOrderByCreatedDateDesc(String status);

    List<ClaudePrompt> findByMandatOrderByCreatedDateDesc(String mandat);

    List<ClaudePrompt> findByMandatAndStatusOrderByCreatedDateDesc(String mandat, String status);

    @Query("SELECT MAX(cp.promptNumber) FROM ClaudePrompt cp WHERE cp.mandat = :mandat")
    String findMaxPromptNumberByMandat(@Param("mandat") String mandat);

    @Query("SELECT MAX(cp.promptNumber) FROM ClaudePrompt cp")
    String findMaxPromptNumber();

    @Query("SELECT cp FROM ClaudePrompt cp WHERE cp.mandat = :mandat AND cp.status = 'SENT' AND cp.sentDate < :timeoutThreshold")
    List<ClaudePrompt> findTimedOutPromptsByMandat(@Param("mandat") String mandat, @Param("timeoutThreshold") java.time.LocalDateTime timeoutThreshold);

    @Query("SELECT cp FROM ClaudePrompt cp WHERE cp.status = 'SENT' AND cp.sentDate < :timeoutThreshold")
    List<ClaudePrompt> findTimedOutPrompts(@Param("timeoutThreshold") java.time.LocalDateTime timeoutThreshold);
}
