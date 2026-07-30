/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * ClaudePrompt Entity - tracks prompts sent to Claude Code CLI
 * Used for acknowledgment tracking and retry logic.
 */
@Entity
@Table(name = "claude_prompt", indexes = {
    @Index(name = "idx_claude_prompt_number", columnList = "prompt_number", unique = true),
    @Index(name = "idx_claude_prompt_status", columnList = "status"),
    @Index(name = "idx_claude_prompt_anforderung", columnList = "anforderung_id")
})
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClaudePrompt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "prompt_number", nullable = false, length = 10, unique = true)
    private String promptNumber; // "00001", "00002", etc.

    @Column(name = "anforderung_id", nullable = false)
    private Long anforderungId;

    @Column(name = "mandat", nullable = false, length = 100)
    private String mandat;

    @Column(name = "status", nullable = false, length = 50)
    private String status; // PENDING, SENT, ACKNOWLEDGED, TIMEOUT, FAILED

    @Column(name = "prompt_text", length = 10000)
    private String promptText;

    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @Column(name = "sent_date")
    private LocalDateTime sentDate;

    @Column(name = "acknowledged_date")
    private LocalDateTime acknowledgedDate;

    @Column(name = "timeout_date")
    private LocalDateTime timeoutDate;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "lockfile_path", length = 500)
    private String lockfilePath;
}
