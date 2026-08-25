/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.service;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.anforderungen.entity.Anforderung;
import ch.plaintext.anforderungen.entity.AnforderungApiSettings;
import ch.plaintext.anforderungen.entity.ClaudePrompt;
import ch.plaintext.anforderungen.entity.ConstraintTemplate;
import ch.plaintext.anforderungen.entity.Howto;
import ch.plaintext.anforderungen.repository.AnforderungApiSettingsRepository;
import ch.plaintext.anforderungen.repository.AnforderungRepository;
import ch.plaintext.anforderungen.repository.ClaudePromptRepository;
import ch.plaintext.anforderungen.repository.ConstraintTemplateRepository;
import ch.plaintext.anforderungen.repository.HowtoRepository;
import ch.plaintext.anforderungen.security.ApiTokenHasher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for Claude automation logic
 */
@Service
@Slf4j
public class ClaudeAutomationService {

    @Autowired
    private AnforderungRepository anforderungRepository;

    @Autowired
    private ClaudePromptRepository claudePromptRepository;

    @Autowired
    private ConstraintTemplateRepository constraintTemplateRepository;

    @Autowired
    private AnforderungApiSettingsRepository apiSettingsRepository;

    @Autowired
    private HowtoRepository howtoRepository;

    @Autowired
    private PlaintextSecurity security;

    /**
     * Generate API token for an Anforderung
     */
    public String generateApiToken() {
        return UUID.randomUUID().toString();
    }

    /**
     * Validate API token against mandat settings
     * For nosec endpoints (without auth), search by token across all mandats
     *
     * <p>H3-Härtung: der Vergleich läuft serverseitig NUR noch über den SHA-256-Hash des
     * Tokens und konstantzeitig ({@code MessageDigest.isEqual} via {@link ApiTokenHasher}).
     * Alt-Zeilen ohne Hash werden beim ersten erfolgreichen Klartext-Match automatisch
     * migriert (Hash geschrieben, Lazy-Migration).</p>
     */
    public boolean validateToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }

        // For nosec endpoints: Find settings by token (works across all mandats)
        if (getSettingsByToken(token).isPresent()) {
            return true;
        }

        // Fallback: Try mandat-based lookup (for authenticated endpoints)
        Optional<AnforderungApiSettings> settings = getSettings();
        return settings.isPresent() && tokenMatchesSettings(token, settings.get());
    }

    /**
     * Get settings for current mandat
     */
    private Optional<AnforderungApiSettings> getSettings() {
        String mandat = getCurrentMandat();
        return apiSettingsRepository.findByMandat(mandat);
    }

    /**
     * Get settings by token (for nosec endpoints).
     *
     * <p>Kanonischer Weg: Lookup + konstantzeitiger Vergleich über den SHA-256-Hash.
     * Übergangsphase: Alt-Zeilen, die nur den Klartext-Token tragen (Bestand vor der
     * Hash-Migration), matchen weiterhin — konstantzeitig — und bekommen dabei den Hash
     * nachgezogen (Lazy-Migration), sodass alle Folge-Requests über den Hash laufen.</p>
     */
    private Optional<AnforderungApiSettings> getSettingsByToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return Optional.empty();
        }
        String tokenHash = ApiTokenHasher.sha256Hex(token);

        Optional<AnforderungApiSettings> byHash = apiSettingsRepository.findByApiTokenHash(tokenHash);
        if (byHash.isPresent()
                && ApiTokenHasher.constantTimeEquals(tokenHash, byHash.get().getApiTokenHash())) {
            return byHash;
        }

        // Legacy-Fallback (Übergangsphase): Klartext-Match, konstantzeitig verglichen.
        Optional<AnforderungApiSettings> byPlaintext = apiSettingsRepository.findByApiToken(token);
        if (byPlaintext.isPresent()
                && ApiTokenHasher.constantTimeEquals(token, byPlaintext.get().getApiToken())) {
            migrateTokenHash(byPlaintext.get(), tokenHash);
            return byPlaintext;
        }

        return Optional.empty();
    }

    /**
     * Hash-bewusster, konstantzeitiger Token-Vergleich gegen eine konkrete Settings-Zeile:
     * Hash vorhanden → nur Hash-Vergleich; sonst Legacy-Klartext-Vergleich + Lazy-Migration.
     */
    private boolean tokenMatchesSettings(String token, AnforderungApiSettings settings) {
        String tokenHash = ApiTokenHasher.sha256Hex(token);
        String storedHash = settings.getApiTokenHash();
        if (storedHash != null && !storedHash.isBlank()) {
            return ApiTokenHasher.constantTimeEquals(tokenHash, storedHash);
        }
        if (ApiTokenHasher.constantTimeEquals(token, settings.getApiToken())) {
            migrateTokenHash(settings, tokenHash);
            return true;
        }
        return false;
    }

    /**
     * Lazy-Migration: schreibt den SHA-256-Hash in Alt-Zeilen, die bisher nur den
     * Klartext-Token tragen. Fehler beim Persistieren dürfen die (bereits erfolgreiche)
     * Validierung NICHT brechen — der aktive Automation-Client muss weiterlaufen.
     */
    private void migrateTokenHash(AnforderungApiSettings settings, String tokenHash) {
        if (tokenHash.equals(settings.getApiTokenHash())) {
            return;
        }
        try {
            settings.setApiTokenHash(tokenHash);
            apiSettingsRepository.save(settings);
            log.info("api_token_hash für Mandant '{}' nachgezogen (Lazy-Migration nach Klartext-Match)",
                    settings.getMandat());
        } catch (Exception e) {
            log.warn("api_token_hash für Mandant '{}' konnte nicht persistiert werden - Validierung läuft weiter",
                    settings.getMandat(), e);
        }
    }

    /**
     * Get mandat from token (for nosec/API endpoints)
     * Returns empty Optional if token is invalid
     */
    public Optional<String> getMandatFromToken(String token) {
        return getSettingsByToken(token).map(AnforderungApiSettings::getMandat);
    }

    /**
     * Mandanten-Isolation: prüft, dass die Anforderung dem Mandanten des Tokens gehört.
     * Verhindert cross-tenant-Zugriff über die {@code /nosec/api/claude}-by-id-Endpoints (IDOR).
     */
    private boolean anforderungGehoertZuToken(Anforderung anf, String token) {
        String tokenMandat = getMandatFromToken(token).orElse(null);
        return tokenMandat != null && anf != null && tokenMandat.equals(anf.getMandat());
    }

    /**
     * Get current mandat from security context
     */
    private String getCurrentMandat() {
        String mandat = security.getMandat();
        if (mandat == null || "NO_AUTH".equals(mandat) || "NO_USER".equals(mandat) || "ERROR".equals(mandat)) {
            // For API calls without auth context, we cannot determine mandat
            log.warn("Cannot determine mandat from security context: {}", mandat);
            return "MIGRATION"; // Fallback for migration phase
        }
        return mandat;
    }

    /**
     * Get next prompt number (sequential: 00001, 00002, etc.)
     * Now per-mandat to support multi-tenancy
     */
    public String getNextPromptNumber(String mandat) {
        String maxNumber = claudePromptRepository.findMaxPromptNumberByMandat(mandat);
        if (maxNumber == null) {
            return "00001";
        }
        try {
            int next = Integer.parseInt(maxNumber) + 1;
            return String.format("%05d", next);
        } catch (NumberFormatException e) {
            log.error("Invalid prompt number format: " + maxNumber, e);
            return "00001";
        }
    }

    /**
     * Get constraint template content for an anforderung
     */
    public String getConstraintTemplateContent(Anforderung anforderung) {
        if (anforderung.getConstraintTemplateId() == null) {
            return "";
        }

        Optional<ConstraintTemplate> template = constraintTemplateRepository.findById(anforderung.getConstraintTemplateId());
        return template.map(ConstraintTemplate::getConstraintsContent).orElse("");
    }

    /**
     * Create full context including default anforderung, constraint template, and specific anforderung
     */
    public String createFullContext(Anforderung anforderung) {
        StringBuilder context = new StringBuilder();

        // Add constraint template if exists
        String constraintContent = getConstraintTemplateContent(anforderung);
        if (!constraintContent.isEmpty()) {
            context.append("=== CONSTRAINT TEMPLATE ===\n");
            context.append(constraintContent);
            context.append("\n\n");
        }

        // Add howtos if selected
        List<Howto> howtos = getHowtosForAnforderung(anforderung);
        if (!howtos.isEmpty()) {
            context.append("=== HOWTOS - HANDLUNGSANWEISUNGEN ===\n");
            context.append("Die folgenden Howtos sollen als Handlungsanweisungen VOR der Bearbeitung der Anforderung beachtet werden:\n\n");
            for (Howto howto : howtos) {
                context.append("## ").append(howto.getName()).append("\n");
                context.append(howto.getText()).append("\n\n");
                if (howto.getBeispiel() != null && !howto.getBeispiel().isEmpty()) {
                    context.append("...\n\n");
                    context.append(howto.getBeispiel()).append("\n\n");
                }
            }
        }

        // Add specific anforderung content
        context.append("=== SPECIFIC ANFORDERUNG ===\n");
        context.append(createPromptText(anforderung));

        // Add extended context fields
        if (anforderung.getTargetModules() != null && !anforderung.getTargetModules().isEmpty()) {
            context.append("\n\nTarget Modules: ").append(anforderung.getTargetModules());
        }

        if (anforderung.getBranchNamingPattern() != null && !anforderung.getBranchNamingPattern().isEmpty()) {
            context.append("\n\nBranch Naming Pattern: ").append(anforderung.getBranchNamingPattern());
        }

        if (anforderung.getDevelopmentCycleInfo() != null && !anforderung.getDevelopmentCycleInfo().isEmpty()) {
            context.append("\n\nDevelopment Cycle Info:\n").append(anforderung.getDevelopmentCycleInfo());
        }

        // Add user answer if exists (from previous iteration)
        if (anforderung.getUserAnswer() != null && !anforderung.getUserAnswer().isEmpty()) {
            context.append("\n\n=== USER FEEDBACK FROM PREVIOUS ITERATION ===\n");
            context.append(anforderung.getUserAnswer());
        }

        return context.toString();
    }

    /**
     * Get howtos for an anforderung
     */
    public List<Howto> getHowtosForAnforderung(Anforderung anforderung) {
        if (anforderung.getHowtoIds() == null || anforderung.getHowtoIds().trim().isEmpty()) {
            return List.of();
        }

        try {
            List<Long> ids = java.util.Arrays.stream(anforderung.getHowtoIds().split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::valueOf)
                    .toList();

            return howtoRepository.findAllById(ids);
        } catch (Exception e) {
            log.error("Error loading howtos for anforderung {}: {}", anforderung.getId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Get howtos by anforderung ID (for REST endpoint)
     */
    public List<Howto> getHowtosForAnforderung(Long anforderungId) {
        Optional<Anforderung> anfOpt = anforderungRepository.findById(anforderungId);
        return anfOpt.map(this::getHowtosForAnforderung).orElse(List.of());
    }

    /**
     * Create prompt text from template with placeholder replacement
     */
    public String createPromptText(Anforderung anforderung) {
        // Use default template
        String template = "Titel: {titel}\nBeschreibung: {beschreibung}\nStatus: {status}\nPriority: {priority}";

        return template
                .replace("{id}", String.valueOf(anforderung.getId()))
                .replace("{titel}", nvl(anforderung.getTitel()))
                .replace("{beschreibung}", nvl(anforderung.getBeschreibung()))
                .replace("{status}", nvl(anforderung.getStatus()))
                .replace("{priority}", nvl(anforderung.getPriority()))
                .replace("{kategorie}", nvl(anforderung.getKategorie()))
                .replace("{ersteller}", nvl(anforderung.getErsteller()))
                .replace("{mandat}", nvl(anforderung.getMandat()));
    }

    private String nvl(String value) {
        return value != null ? value : "";
    }

    /**
     * Create and save new ClaudePrompt for Anforderung
     */
    @Transactional
    public ClaudePrompt createPrompt(Long anforderungId) {
        Optional<Anforderung> anfOpt = anforderungRepository.findById(anforderungId);
        if (anfOpt.isEmpty()) {
            throw new IllegalArgumentException("Anforderung not found: " + anforderungId);
        }

        Anforderung anf = anfOpt.get();
        String promptNumber = getNextPromptNumber(anf.getMandat());
        String promptText = createFullContext(anf);

        ClaudePrompt prompt = new ClaudePrompt();
        prompt.setPromptNumber(promptNumber);
        prompt.setAnforderungId(anforderungId);
        prompt.setMandat(anf.getMandat());
        prompt.setStatus("PENDING");
        prompt.setPromptText(promptText);
        prompt.setCreatedDate(LocalDateTime.now());
        prompt.setRetryCount(0);
        prompt.setLockfilePath("/Users/mad/Desktop/claude-task.lock");

        return claudePromptRepository.save(prompt);
    }

    /**
     * Mark prompt as sent
     */
    @Transactional
    public void markPromptSent(String promptNumber) {
        Optional<ClaudePrompt> opt = claudePromptRepository.findByPromptNumber(promptNumber);
        if (opt.isPresent()) {
            ClaudePrompt prompt = opt.get();
            prompt.setStatus("SENT");
            prompt.setSentDate(LocalDateTime.now());
            prompt.setTimeoutDate(LocalDateTime.now().plusMinutes(15));
            claudePromptRepository.save(prompt);
        }
    }

    /**
     * Acknowledge prompt (called from REST API)
     */
    @Transactional
    public boolean acknowledgePrompt(String promptNumber, String token) {
        if (!validateToken(token)) {
            log.warn("Invalid token for prompt acknowledgment: {}", promptNumber);
            return false;
        }

        Optional<ClaudePrompt> opt = claudePromptRepository.findByPromptNumber(promptNumber);
        if (opt.isEmpty()) {
            log.warn("Prompt not found: {}", promptNumber);
            return false;
        }

        ClaudePrompt prompt = opt.get();
        prompt.setStatus("ACKNOWLEDGED");
        prompt.setAcknowledgedDate(LocalDateTime.now());
        claudePromptRepository.save(prompt);

        // Update anforderung last execution date
        Optional<Anforderung> anfOpt = anforderungRepository.findById(prompt.getAnforderungId());
        if (anfOpt.isPresent()) {
            Anforderung anf = anfOpt.get();
            anf.setLastExecutionDate(LocalDateTime.now());
            anforderungRepository.save(anf);
        }

        log.info("Prompt {} acknowledged successfully", promptNumber);
        return true;
    }

    /**
     * Get prompt status
     */
    public String getPromptStatus(String promptNumber) {
        Optional<ClaudePrompt> opt = claudePromptRepository.findByPromptNumber(promptNumber);
        return opt.map(ClaudePrompt::getStatus).orElse("NOT_FOUND");
    }

    /**
     * Check if prompt is acknowledged
     */
    public boolean isPromptAcknowledged(String promptNumber) {
        return "ACKNOWLEDGED".equals(getPromptStatus(promptNumber));
    }

    /**
     * Check if prompt timed out
     */
    public boolean isPromptTimedOut(String promptNumber) {
        Optional<ClaudePrompt> opt = claudePromptRepository.findByPromptNumber(promptNumber);
        if (opt.isEmpty()) {
            return false;
        }

        ClaudePrompt prompt = opt.get();
        if (!"SENT".equals(prompt.getStatus())) {
            return false;
        }

        return prompt.getTimeoutDate() != null &&
               LocalDateTime.now().isAfter(prompt.getTimeoutDate());
    }

    /**
     * Get next task (anforderung) for automation
     * Returns anforderung that needs execution (checks global settings)
     * Tasks are prioritized by: KRITISCH > HOCH > MITTEL > NIEDRIG
     *
     * Also checks for recurring tasks (wiederkehrend=true) that are ERLEDIGT
     * and reopens them if lastModifiedDate is older than wiederkehrendTage days
     */
    public Optional<Anforderung> getNextTask(String token) {
        if (!validateToken(token)) {
            log.warn("Invalid token for getting next task");
            return Optional.empty();
        }

        // Get settings by token (works for nosec endpoints)
        Optional<AnforderungApiSettings> settingsOpt = getSettingsByToken(token);
        if (settingsOpt.isEmpty() || !Boolean.TRUE.equals(settingsOpt.get().getClaudeAutomationEnabled())) {
            log.debug("Claude automation is disabled for this token/mandat");
            return Optional.empty();
        }

        // Get mandat from settings
        String mandat = settingsOpt.get().getMandat();
        log.info("Getting next task for mandat: {}", mandat);

        // Find all anforderungen for this mandat
        List<Anforderung> allAnforderungen = anforderungRepository.findByMandatOrderByCreatedDateDesc(mandat);
        log.debug("Found {} anforderungen total", allAnforderungen.size());

        // Check for recurring tasks that need to be reopened
        checkAndReopenRecurringTasks(allAnforderungen);

        List<Anforderung> tasks = allAnforderungen.stream()
                .filter(a -> {
                    boolean notDone = !"ERLEDIGT".equals(a.getStatus());
                    boolean notFeedback = !"FEEDBACK".equals(a.getStatus());
                    boolean eligible = notDone && notFeedback;
                    log.debug("Anforderung {}: status={}, eligible={}", a.getId(), a.getStatus(), eligible);
                    return eligible;
                })
                .sorted((a, b) -> {
                    // Priority order: KRITISCH > HOCH > MITTEL > NIEDRIG
                    int priorityA = getPriorityWeight(a.getPriority());
                    int priorityB = getPriorityWeight(b.getPriority());
                    return Integer.compare(priorityB, priorityA); // Descending order (higher priority first)
                })
                .toList();

        log.info("Found {} tasks ready for automation", tasks.size());
        if (!tasks.isEmpty()) {
            log.info("Next task selected: ID={}, Priority={}, Titel={}",
                    tasks.get(0).getId(), tasks.get(0).getPriority(), tasks.get(0).getTitel());
        }
        return tasks.isEmpty() ? Optional.empty() : Optional.of(tasks.get(0));
    }

    /**
     * Check all recurring tasks (wiederkehrend=true) that are ERLEDIGT
     * and reopen them if lastModifiedDate is older than wiederkehrendTage days
     *
     * <p><b>Karte 968 (Sonar {@code java:S6809}): das {@code @Transactional} ist entfallen.</b> Es
     * stand an einer <i>privaten</i> Methode und war damit doppelt wirkungslos — Spring legt seinen
     * Proxy um die Bean, private Methoden erreicht er nie, und gerufen wurde sie zusaetzlich per
     * Selbstaufruf aus {@link #getNextTask(String)}, die selbst keine Klammer aufspannt. Es gab
     * hier also nie eine Transaktion; die Annotation behauptete nur eine.
     *
     * <p><b>Warum sie nicht stattdessen nach oben gewandert ist.</b> Jeder
     * {@code anforderungRepository.save(...)} laeuft ohnehin in seiner eigenen Transaktion
     * (Spring Data). Faellt der Lauf in der Mitte aus, sind einige Aufgaben wieder offen und
     * andere noch nicht — das ist hier harmlos, weil der naechste Aufruf genau dieselbe Pruefung
     * erneut macht und den Rest nachholt. Eine Klammer um {@code getNextTask} haette dafuer die
     * gesamte Leseschleife in eine Transaktion gezogen und die zurueckgegebene Anforderung
     * verwaltet gehalten. Wer Atomizitaet wirklich braucht, muss die Klammer an eine
     * <b>oeffentliche</b> Methode haengen — an dieser hier wuerde sie wieder nichts tun.
     */
    private void checkAndReopenRecurringTasks(List<Anforderung> allAnforderungen) {
        LocalDateTime now = LocalDateTime.now();

        for (Anforderung anf : allAnforderungen) {
            // Only process recurring tasks that are ERLEDIGT
            if (Boolean.TRUE.equals(anf.getWiederkehrend()) &&
                "ERLEDIGT".equals(anf.getStatus()) &&
                anf.getWiederkehrendTage() != null &&
                anf.getWiederkehrendTage() > 0 &&
                anf.getLastModifiedDate() != null) {

                // Calculate days since last modification
                LocalDateTime threshold = now.minusDays(anf.getWiederkehrendTage());

                if (anf.getLastModifiedDate().isBefore(threshold)) {
                    // Reopen the task
                    log.info("Reopening recurring task ID={}, Titel={} - last modified {} days ago",
                            anf.getId(), anf.getTitel(),
                            java.time.temporal.ChronoUnit.DAYS.between(anf.getLastModifiedDate(), now));

                    anf.setStatus("OFFEN");
                    anforderungRepository.save(anf);

                    log.info("Recurring task ID={} reopened successfully", anf.getId());
                }
            }
        }
    }

    /**
     * Get numeric weight for priority sorting
     * KRITISCH=4, HOCH=3, MITTEL=2, NIEDRIG=1, null/unknown=0
     */
    private int getPriorityWeight(String priority) {
        if (priority == null) {
            return 0;
        }
        return switch (priority) {
            case "KRITISCH" -> 4;
            case "HOCH" -> 3;
            case "MITTEL" -> 2;
            case "NIEDRIG" -> 1;
            default -> 0;
        };
    }

    /**
     * Check if there is work available for automation
     */
    public boolean hasWork(String token) {
        return getNextTask(token).isPresent();
    }

    /**
     * Get all prompts for an anforderung
     */
    public List<ClaudePrompt> getPromptsForAnforderung(Long anforderungId) {
        return claudePromptRepository.findByAnforderungIdOrderByCreatedDateDesc(anforderungId);
    }

    /**
     * Get all prompts for current mandat (for settings view)
     */
    public List<ClaudePrompt> getAllPrompts() {
        String mandat = getCurrentMandat();
        return claudePromptRepository.findByMandatOrderByCreatedDateDesc(mandat);
    }

    /**
     * Retry timed out prompt
     */
    @Transactional
    public void retryPrompt(String promptNumber) {
        Optional<ClaudePrompt> opt = claudePromptRepository.findByPromptNumber(promptNumber);
        if (opt.isPresent()) {
            ClaudePrompt prompt = opt.get();
            prompt.setStatus("PENDING");
            prompt.setRetryCount(prompt.getRetryCount() + 1);
            prompt.setTimeoutDate(null);
            claudePromptRepository.save(prompt);
            log.info("Prompt {} marked for retry (attempt {})", promptNumber, prompt.getRetryCount());
        }
    }

    /**
     * Handle timeout - mark as TIMEOUT and create retry if count < 3
     */
    @Transactional
    public void handleTimeout(String promptNumber) {
        Optional<ClaudePrompt> opt = claudePromptRepository.findByPromptNumber(promptNumber);
        if (opt.isEmpty()) {
            return;
        }

        ClaudePrompt prompt = opt.get();
        prompt.setStatus("TIMEOUT");
        claudePromptRepository.save(prompt);

        if (prompt.getRetryCount() < 3) {
            log.info("Prompt {} timed out - will retry after 15 minutes", promptNumber);
        } else {
            log.error("Prompt {} timed out after 3 retries - marking as FAILED", promptNumber);
            prompt.setStatus("FAILED");
            prompt.setErrorMessage("Timed out after 3 retries");
            claudePromptRepository.save(prompt);
        }
    }

    /**
     * Save Claude's summary for a prompt
     */
    @Transactional
    public boolean saveSummary(String promptNumber, String summary, String token) {
        if (!validateToken(token)) {
            log.warn("Invalid token for saving summary: {}", promptNumber);
            return false;
        }

        Optional<ClaudePrompt> promptOpt = claudePromptRepository.findByPromptNumber(promptNumber);
        if (promptOpt.isEmpty()) {
            log.warn("Prompt not found for summary: {}", promptNumber);
            return false;
        }

        ClaudePrompt prompt = promptOpt.get();
        Optional<Anforderung> anfOpt = anforderungRepository.findById(prompt.getAnforderungId());
        if (anfOpt.isEmpty()) {
            log.warn("Anforderung not found for prompt: {}", promptNumber);
            return false;
        }

        Anforderung anf = anfOpt.get();

        // Create timestamped summary entry
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String newSummaryEntry = String.format("### Summary vom %s (Prompt: %s)\n%s\n\n",
                                               timestamp, promptNumber, summary);

        // Append to existing summaries (keep history)
        String existingSummary = anf.getClaudeSummary();
        if (existingSummary != null && !existingSummary.isEmpty()) {
            anf.setClaudeSummary(existingSummary + newSummaryEntry);
            log.info("Appended summary to existing history for anforderung {}", anf.getId());
        } else {
            anf.setClaudeSummary(newSummaryEntry);
            log.info("Created first summary for anforderung {}", anf.getId());
        }

        // Note: Global automation setting is now in AnforderungApiSettings
        // requiresUserAnswer flag stays on Anforderung for tracking purposes
        if (Boolean.TRUE.equals(anf.getRequiresUserAnswer())) {
            log.info("Anforderung {} requires user answer", anf.getId());
        }

        anforderungRepository.save(anf);
        log.info("Summary saved for prompt {} and anforderung {}", promptNumber, anf.getId());
        return true;
    }

    /**
     * Save user's answer/feedback and re-enable automation
     * WICHTIG: Der "answer" Parameter ist die FRAGE von Claude an den User (nicht die Antwort des Users)
     */
    @Transactional
    public boolean saveUserAnswer(Long anforderungId, String answer, String token) {
        if (!validateToken(token)) {
            log.warn("Invalid token for saving user answer: {}", anforderungId);
            return false;
        }

        Optional<Anforderung> anfOpt = anforderungRepository.findById(anforderungId);
        if (anfOpt.isEmpty()) {
            log.warn("Anforderung not found: {}", anforderungId);
            return false;
        }

        Anforderung anf = anfOpt.get();
        if (!anforderungGehoertZuToken(anf, token)) {
            log.warn("Cross-tenant access denied: saveUserAnswer für Anforderung {} passt nicht zum Token-Mandanten", anforderungId);
            return false;
        }
        anf.setUserAnswer(answer);
        anf.setRequiresUserAnswer(false); // Clear the flag
        anf.setNextExecutionDate(LocalDateTime.now()); // Schedule for immediate processing

        // Erstelle ClaudePrompt-Eintrag mit der Frage von Claude
        // Damit kann die Frage später in der UI angezeigt werden (anforderungdetail.xhtml)
        try {
            ClaudePrompt questionPrompt = new ClaudePrompt();
            questionPrompt.setPromptNumber("FEEDBACK-" + anforderungId + "-" + System.currentTimeMillis());
            questionPrompt.setAnforderungId(anforderungId);
            questionPrompt.setMandat(anf.getMandat());
            questionPrompt.setStatus("FEEDBACK_QUESTION");
            questionPrompt.setPromptText(answer); // Die Frage von Claude
            questionPrompt.setCreatedDate(LocalDateTime.now());

            claudePromptRepository.save(questionPrompt);
            log.info("Claude feedback question saved as prompt for anforderung {}", anforderungId);
        } catch (Exception e) {
            log.error("Error saving Claude feedback question as prompt", e);
            // Fahre trotzdem fort - ist kein kritischer Fehler
        }

        anforderungRepository.save(anf);
        log.info("User answer saved for anforderung {}", anforderungId);
        return true;
    }

    /**
     * Get full context for an anforderung (for external tools/scripts)
     */
    public String getFullContextForAnforderung(Long anforderungId, String token) {
        if (!validateToken(token)) {
            log.warn("Invalid token for getting context: {}", anforderungId);
            return null;
        }

        Optional<Anforderung> anfOpt = anforderungRepository.findById(anforderungId);
        if (anfOpt.isEmpty()) {
            log.warn("Anforderung not found: {}", anforderungId);
            return null;
        }
        if (!anforderungGehoertZuToken(anfOpt.get(), token)) {
            log.warn("Cross-tenant access denied: getFullContext für Anforderung {} passt nicht zum Token-Mandanten", anforderungId);
            return null;
        }

        return createFullContext(anfOpt.get());
    }

    /**
     * Update anforderung status
     */
    @Transactional
    public boolean updateAnforderungStatus(Long anforderungId, String status, String token) {
        if (!validateToken(token)) {
            log.warn("Invalid token for updating status: {}", anforderungId);
            return false;
        }

        // Validate status
        if (!List.of("OFFEN", "IN_BEARBEITUNG", "FEEDBACK", "ERLEDIGT", "ABGELEHNT").contains(status)) {
            log.warn("Invalid status: {}", status);
            return false;
        }

        Optional<Anforderung> anfOpt = anforderungRepository.findById(anforderungId);
        if (anfOpt.isEmpty()) {
            log.warn("Anforderung not found: {}", anforderungId);
            return false;
        }

        Anforderung anf = anfOpt.get();
        if (!anforderungGehoertZuToken(anf, token)) {
            log.warn("Cross-tenant access denied: updateStatus für Anforderung {} passt nicht zum Token-Mandanten", anforderungId);
            return false;
        }
        anf.setStatus(status);
        anforderungRepository.save(anf);

        log.info("Updated anforderung {} status to: {}", anforderungId, status);
        return true;
    }

    /**
     * Create new anforderung via API
     * Returns the ID of the created anforderung, or null if creation failed
     */
    @Transactional
    public Long createAnforderung(String token, String titel, String beschreibung, String priority, String kategorie, Boolean wiederkehrend) {
        if (!validateToken(token)) {
            log.warn("Invalid token for creating anforderung");
            return null;
        }

        // Get mandat from token
        Optional<AnforderungApiSettings> settingsOpt = getSettingsByToken(token);
        if (settingsOpt.isEmpty()) {
            log.warn("No settings found for token");
            return null;
        }

        String mandat = settingsOpt.get().getMandat();
        log.info("Creating anforderung for mandat: {}", mandat);

        // Validate required fields
        if (titel == null || titel.trim().isEmpty()) {
            log.warn("Titel is required for creating anforderung");
            return null;
        }

        // Validate priority if provided
        if (priority != null && !priority.trim().isEmpty()) {
            if (!List.of("NIEDRIG", "MITTEL", "HOCH", "KRITISCH").contains(priority)) {
                log.warn("Invalid priority: {}. Using MITTEL as default.", priority);
                priority = "MITTEL";
            }
        } else {
            priority = "MITTEL"; // Default priority
        }

        // Create new anforderung
        Anforderung anforderung = new Anforderung();
        anforderung.setMandat(mandat);
        anforderung.setTitel(titel);
        anforderung.setBeschreibung(beschreibung != null ? beschreibung : "");
        anforderung.setStatus("OFFEN");
        anforderung.setPriority(priority);
        anforderung.setKategorie(kategorie);
        anforderung.setWiederkehrend(wiederkehrend != null ? wiederkehrend : false);
        anforderung.setErsteller("API"); // Mark as created via API
        anforderung.setRequiresUserAnswer(false);

        Anforderung saved = anforderungRepository.save(anforderung);
        log.info("Created anforderung {} with titel: {}", saved.getId(), titel);

        return saved.getId();
    }

}
