/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.rest;

import ch.plaintext.anforderungen.entity.Anforderung;
import ch.plaintext.anforderungen.entity.ClaudePrompt;
import ch.plaintext.anforderungen.entity.Howto;
import ch.plaintext.anforderungen.repository.HowtoRepository;
import ch.plaintext.anforderungen.service.ClaudeAutomationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * REST Controller for Claude automation endpoints
 * Used by watch-claude.sh script
 */
@RestController
@RequestMapping("/nosec/api/claude")
@Slf4j
@Tag(name = "Claude Automation", description = "API für Claude Code Automatisierung - Verwaltung von Anforderungen und Prompts")
public class ClaudeAutomationController {

    @Autowired
    private ClaudeAutomationService service;

    @Autowired
    private HowtoRepository howtoRepository;

    /**
     * Acknowledge a prompt - called by Claude Code via curl
     * curl -X POST http://localhost:8080/api/claude/ack/00042?token=xxx
     */
    @Operation(summary = "Prompt bestätigen", description = "Bestätigt, dass ein Prompt von Claude Code empfangen wurde")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Prompt erfolgreich bestätigt"),
            @ApiResponse(responseCode = "401", description = "Ungültiger Token oder Prompt nicht gefunden")
    })
    @PostMapping("/ack/{promptNumber}")
    public ResponseEntity<AckResponse> acknowledgePrompt(
            @Parameter(description = "Prompt-Nummer (z.B. 00042)", required = true) @PathVariable String promptNumber,
            @Parameter(description = "Authentifizierungs-Token", required = true) @RequestParam String token) {

        log.info("Received acknowledgment for prompt: {}", promptNumber);

        boolean success = service.acknowledgePrompt(promptNumber, token);

        AckResponse response = new AckResponse();
        response.setSuccess(success);
        response.setPromptNumber(promptNumber);
        response.setMessage(success ? "Prompt acknowledged" : "Invalid token or prompt not found");

        return success
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    /**
     * Get prompt status
     * curl http://localhost:8080/api/claude/ack/00042/status?token=xxx
     */
    @Operation(summary = "Prompt-Status abfragen", description = "Liefert den aktuellen Status eines Prompts")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Status erfolgreich abgerufen"),
            @ApiResponse(responseCode = "401", description = "Ungültiger Token")
    })
    @GetMapping("/ack/{promptNumber}/status")
    public ResponseEntity<StatusResponse> getPromptStatus(
            @Parameter(description = "Prompt-Nummer (z.B. 00042)", required = true) @PathVariable String promptNumber,
            @Parameter(description = "Authentifizierungs-Token", required = true) @RequestParam String token) {

        if (!service.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String status = service.getPromptStatus(promptNumber);
        boolean acknowledged = service.isPromptAcknowledged(promptNumber);
        boolean timedOut = service.isPromptTimedOut(promptNumber);

        StatusResponse response = new StatusResponse();
        response.setPromptNumber(promptNumber);
        response.setStatus(status);
        response.setAcknowledged(acknowledged);
        response.setTimedOut(timedOut);

        return ResponseEntity.ok(response);
    }

    /**
     * Check if there is work available
     * curl http://localhost:8080/api/claude/has-work?token=xxx
     */
    @Operation(summary = "Arbeit verfügbar prüfen", description = "Prüft, ob offene Anforderungen zur Bearbeitung vorhanden sind")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Prüfung erfolgreich"),
            @ApiResponse(responseCode = "401", description = "Ungültiger Token")
    })
    @GetMapping("/has-work")
    public ResponseEntity<WorkResponse> hasWork(
            @Parameter(description = "Authentifizierungs-Token", required = true) @RequestParam String token) {
        if (!service.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        boolean hasWork = service.hasWork(token);

        WorkResponse response = new WorkResponse();
        response.setHasWork(hasWork);

        return ResponseEntity.ok(response);
    }

    /**
     * Get next task for automation
     * curl http://localhost:8080/api/claude/next-task?token=xxx
     */
    @Operation(summary = "Nächste Aufgabe abrufen", description = "Liefert die nächste offene Anforderung zur Bearbeitung durch Claude Code")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Aufgabe erfolgreich abgerufen"),
            @ApiResponse(responseCode = "204", description = "Keine Aufgabe verfügbar"),
            @ApiResponse(responseCode = "401", description = "Ungültiger Token")
    })
    @GetMapping("/next-task")
    public ResponseEntity<TaskResponse> getNextTask(
            @Parameter(description = "Authentifizierungs-Token", required = true) @RequestParam String token) {
        if (!service.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        Optional<Anforderung> taskOpt = service.getNextTask(token);
        if (taskOpt.isEmpty()) {
            return ResponseEntity.noContent().build();
        }

        Anforderung task = taskOpt.get();
        ClaudePrompt prompt = service.createPrompt(task.getId());
        service.markPromptSent(prompt.getPromptNumber());

        TaskResponse response = new TaskResponse();
        response.setAnforderungId(task.getId());
        response.setTitel(task.getTitel());
        response.setBeschreibung(task.getBeschreibung());
        response.setPromptNumber(prompt.getPromptNumber());
        response.setPromptText(prompt.getPromptText());
        response.setLockfilePath(prompt.getLockfilePath());

        return ResponseEntity.ok(response);
    }

    /**
     * Get next prompt number (for testing)
     * curl http://localhost:8080/api/claude/next-prompt-number?token=xxx
     */
    @Operation(summary = "Nächste Prompt-Nummer abrufen", description = "Liefert die nächste verfügbare Prompt-Nummer (zu Testzwecken)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Prompt-Nummer erfolgreich abgerufen"),
            @ApiResponse(responseCode = "401", description = "Ungültiger Token")
    })
    @GetMapping("/next-prompt-number")
    public ResponseEntity<Map<String, String>> getNextPromptNumber(
            @Parameter(description = "Authentifizierungs-Token", required = true) @RequestParam String token) {
        if (!service.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Get mandat from token
        Optional<String> mandatOpt = service.getMandatFromToken(token);
        if (mandatOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String promptNumber = service.getNextPromptNumber(mandatOpt.get());
        Map<String, String> response = new HashMap<>();
        response.put("promptNumber", promptNumber);

        return ResponseEntity.ok(response);
    }

    /**
     * Save Claude's summary after completing work
     * curl -X POST "http://localhost:8080/api/claude/summary/00042?token=xxx" -d "summary=Work completed successfully"
     */
    @Operation(summary = "Zusammenfassung speichern", description = "Speichert die Zusammenfassung nach Abschluss der Arbeit durch Claude Code")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Zusammenfassung erfolgreich gespeichert"),
            @ApiResponse(responseCode = "400", description = "Fehler beim Speichern der Zusammenfassung")
    })
    @PostMapping("/summary/{promptNumber}")
    public ResponseEntity<SummaryResponse> saveSummary(
            @Parameter(description = "Prompt-Nummer (z.B. 00042)", required = true) @PathVariable String promptNumber,
            @Parameter(description = "Authentifizierungs-Token", required = true) @RequestParam String token,
            @Parameter(description = "Zusammenfassung der durchgeführten Arbeit", required = true) @RequestParam String summary) {

        log.info("Received summary for prompt: {}", promptNumber);

        boolean success = service.saveSummary(promptNumber, summary, token);

        SummaryResponse response = new SummaryResponse();
        response.setSuccess(success);
        response.setPromptNumber(promptNumber);
        response.setMessage(success ? "Summary saved successfully" : "Failed to save summary");

        return success
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Save user's answer/feedback and re-enable automation
     * curl -X POST "http://localhost:8080/api/claude/answer/123?token=xxx" -d "answer=Please fix the bug in login module"
     */
    @Operation(summary = "Benutzerantwort speichern", description = "Speichert die Antwort/Rückmeldung des Benutzers und aktiviert die Automatisierung wieder")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Antwort erfolgreich gespeichert, Automatisierung reaktiviert"),
            @ApiResponse(responseCode = "400", description = "Fehler beim Speichern der Antwort")
    })
    @PostMapping("/answer/{anforderungId}")
    public ResponseEntity<AnswerResponse> saveUserAnswer(
            @Parameter(description = "Anforderungs-ID", required = true) @PathVariable Long anforderungId,
            @Parameter(description = "Authentifizierungs-Token", required = true) @RequestParam String token,
            @Parameter(description = "Antwort/Rückmeldung des Benutzers", required = true) @RequestParam String answer) {

        log.info("Received user answer for anforderung: {}", anforderungId);

        boolean success = service.saveUserAnswer(anforderungId, answer, token);

        AnswerResponse response = new AnswerResponse();
        response.setSuccess(success);
        response.setAnforderungId(anforderungId);
        response.setMessage(success ? "Answer saved, automation re-enabled" : "Failed to save answer");

        return success
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Get full context for an anforderung (includes default anforderung, constraints, etc.)
     * curl "http://localhost:8080/api/claude/context/123?token=xxx"
     */
    @Operation(summary = "Vollständigen Kontext abrufen", description = "Liefert den vollständigen Kontext einer Anforderung inkl. Standard-Anforderung und Constraints")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Kontext erfolgreich abgerufen"),
            @ApiResponse(responseCode = "401", description = "Ungültiger Token")
    })
    @GetMapping("/context/{anforderungId}")
    public ResponseEntity<ContextResponse> getFullContext(
            @Parameter(description = "Anforderungs-ID", required = true) @PathVariable Long anforderungId,
            @Parameter(description = "Authentifizierungs-Token", required = true) @RequestParam String token) {

        String context = service.getFullContextForAnforderung(anforderungId, token);

        if (context == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        ContextResponse response = new ContextResponse();
        response.setAnforderungId(anforderungId);
        response.setContext(context);

        return ResponseEntity.ok(response);
    }

    /**
     * Update anforderung status
     * curl -X POST "http://localhost:8080/api/claude/status/123?token=xxx&status=ERLEDIGT"
     */
    @Operation(summary = "Anforderungsstatus aktualisieren", description = "Aktualisiert den Status einer Anforderung (OFFEN, FEEDBACK, ERLEDIGT)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Status erfolgreich aktualisiert"),
            @ApiResponse(responseCode = "400", description = "Ungültiger Status"),
            @ApiResponse(responseCode = "401", description = "Ungültiger Token")
    })
    @PostMapping("/status/{anforderungId}")
    public ResponseEntity<StatusUpdateResponse> updateStatus(
            @Parameter(description = "Anforderungs-ID", required = true) @PathVariable Long anforderungId,
            @Parameter(description = "Authentifizierungs-Token", required = true) @RequestParam String token,
            @Parameter(description = "Neuer Status (OFFEN, FEEDBACK, ERLEDIGT)", required = true) @RequestParam String status) {

        log.info("Updating status for anforderung {} to: {}", anforderungId, status);

        boolean success = service.updateAnforderungStatus(anforderungId, status, token);

        StatusUpdateResponse response = new StatusUpdateResponse();
        response.setSuccess(success);
        response.setAnforderungId(anforderungId);
        response.setStatus(status);
        response.setMessage(success ? "Status updated successfully" : "Failed to update status");

        return success
                ? ResponseEntity.ok(response)
                : ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    // Response DTOs
    @Data
    public static class AckResponse {
        private boolean success;
        private String promptNumber;
        private String message;
    }

    @Data
    public static class StatusResponse {
        private String promptNumber;
        private String status;
        private boolean acknowledged;
        private boolean timedOut;
    }

    @Data
    public static class WorkResponse {
        private boolean hasWork;
    }

    @Data
    public static class TaskResponse {
        private Long anforderungId;
        private String titel;
        private String beschreibung;
        private String promptNumber;
        private String promptText;
        private String lockfilePath;
    }

    @Data
    public static class SummaryResponse {
        private boolean success;
        private String promptNumber;
        private String message;
    }

    @Data
    public static class AnswerResponse {
        private boolean success;
        private Long anforderungId;
        private String message;
    }

    @Data
    public static class ContextResponse {
        private Long anforderungId;
        private String context;
    }

    @Data
    public static class StatusUpdateResponse {
        private boolean success;
        private Long anforderungId;
        private String status;
        private String message;
    }

    @Data
    public static class CreateAnforderungResponse {
        private boolean success;
        private Long anforderungId;
        private String message;
    }

    /**
     * Create new anforderung
     * curl -X POST "http://localhost:8080/nosec/api/claude/anforderung?token=xxx&titel=Test&beschreibung=Test"
     */
    @Operation(summary = "Neue Anforderung erstellen", description = "Erstellt eine neue Anforderung für den zum Token gehörenden Mandanten")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Anforderung erfolgreich erstellt"),
            @ApiResponse(responseCode = "400", description = "Fehler beim Erstellen der Anforderung"),
            @ApiResponse(responseCode = "401", description = "Ungültiger Token")
    })
    @PostMapping("/anforderung")
    public ResponseEntity<CreateAnforderungResponse> createAnforderung(
            @Parameter(description = "Authentifizierungs-Token", required = true) @RequestParam String token,
            @Parameter(description = "Titel der Anforderung", required = true) @RequestParam String titel,
            @Parameter(description = "Beschreibung der Anforderung", required = false) @RequestParam(required = false) String beschreibung,
            @Parameter(description = "Priorität (NIEDRIG, MITTEL, HOCH, KRITISCH)", required = false) @RequestParam(required = false) String priority,
            @Parameter(description = "Kategorie", required = false) @RequestParam(required = false) String kategorie,
            @Parameter(description = "Wiederkehrend (true/false)", required = false) @RequestParam(required = false) Boolean wiederkehrend) {

        log.info("Creating new anforderung via API: titel={}", titel);

        Long anforderungId = service.createAnforderung(token, titel, beschreibung, priority, kategorie, wiederkehrend);

        if (anforderungId == null) {
            CreateAnforderungResponse response = new CreateAnforderungResponse();
            response.setSuccess(false);
            response.setMessage("Failed to create anforderung - invalid token or missing data");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        }

        CreateAnforderungResponse response = new CreateAnforderungResponse();
        response.setSuccess(true);
        response.setAnforderungId(anforderungId);
        response.setMessage("Anforderung created successfully");

        return ResponseEntity.ok(response);
    }

    /**
     * Get all active howtos
     * curl http://localhost:8080/nosec/api/claude/howtos?token=xxx
     */
    @Operation(summary = "Liste aller aktiven Howtos", description = "Gibt alle aktiven How-To Anleitungen zurück")
    @GetMapping("/howtos")
    public ResponseEntity<?> getAllHowtos(@RequestParam String token) {
        if (!service.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid token"));
        }

        java.util.List<Howto> howtos = howtoRepository.findByActiveTrue();
        return ResponseEntity.ok(howtos);
    }

    /**
     * Get howtos for a specific anforderung
     * curl http://localhost:8080/nosec/api/claude/anforderung/42/howtos?token=xxx
     */
    @Operation(summary = "Howtos für Anforderung", description = "Gibt alle Howtos einer bestimmten Anforderung zurück")
    @GetMapping("/anforderung/{id}/howtos")
    public ResponseEntity<?> getHowtosForAnforderung(
            @PathVariable Long id,
            @RequestParam String token) {

        if (!service.validateToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Invalid token"));
        }

        java.util.List<Howto> howtos = service.getHowtosForAnforderung(id);
        return ResponseEntity.ok(howtos);
    }
}
