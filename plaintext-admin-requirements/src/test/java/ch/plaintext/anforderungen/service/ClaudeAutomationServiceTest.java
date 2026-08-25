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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClaudeAutomationServiceTest {

    @Mock
    private AnforderungRepository anforderungRepository;

    @Mock
    private ClaudePromptRepository claudePromptRepository;

    @Mock
    private ConstraintTemplateRepository constraintTemplateRepository;

    @Mock
    private AnforderungApiSettingsRepository apiSettingsRepository;

    @Mock
    private HowtoRepository howtoRepository;

    @Mock
    private PlaintextSecurity security;

    @InjectMocks
    private ClaudeAutomationService service;

    // --- generateApiToken ---

    @Test
    void generateApiTokenReturnsNonNullUUID() {
        String token = service.generateApiToken();
        assertThat(token).isNotNull().isNotEmpty();
        // UUID format: 8-4-4-4-12
        assertThat(token).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void generateApiTokenReturnsUniqueTokens() {
        String token1 = service.generateApiToken();
        String token2 = service.generateApiToken();
        assertThat(token1).isNotEqualTo(token2);
    }

    // --- validateToken ---

    @Test
    void validateTokenReturnsFalseForNull() {
        assertThat(service.validateToken(null)).isFalse();
    }

    @Test
    void validateTokenReturnsFalseForEmpty() {
        assertThat(service.validateToken("")).isFalse();
    }

    @Test
    void validateTokenReturnsFalseForBlank() {
        assertThat(service.validateToken("   ")).isFalse();
    }

    @Test
    void validateTokenReturnsTrueWhenFoundByToken() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("valid-token");
        when(apiSettingsRepository.findByApiToken("valid-token")).thenReturn(Optional.of(settings));

        assertThat(service.validateToken("valid-token")).isTrue();
    }

    @Test
    void validateTokenReturnsFalseWhenNotFound() {
        when(apiSettingsRepository.findByApiToken("invalid")).thenReturn(Optional.empty());
        when(security.getMandat()).thenReturn("MIGRATION");
        when(apiSettingsRepository.findByMandat("MIGRATION")).thenReturn(Optional.empty());

        assertThat(service.validateToken("invalid")).isFalse();
    }

    @Test
    void validateTokenFallsBackToMandatLookup() {
        when(apiSettingsRepository.findByApiToken("token-abc")).thenReturn(Optional.empty());
        when(security.getMandat()).thenReturn("mandatA");
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token-abc");
        when(apiSettingsRepository.findByMandat("mandatA")).thenReturn(Optional.of(settings));

        assertThat(service.validateToken("token-abc")).isTrue();
    }

    // --- validateToken: SHA-256-Hash-Vergleich + Lazy-Migration (H3-Haertung) ---

    @Test
    void validateTokenMatchesViaStoredHashWithoutPlaintext() {
        // Zukunfts-Zustand: Zeile traegt nur noch den Hash, keinen Klartext mehr.
        String token = "hashed-token";
        String hash = ApiTokenHasher.sha256Hex(token);
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setMandat("mandatA");
        settings.setApiTokenHash(hash);
        when(apiSettingsRepository.findByApiTokenHash(hash)).thenReturn(Optional.of(settings));

        assertThat(service.validateToken(token)).isTrue();
        // Kein Legacy-Lookup noetig, keine Migration
        verify(apiSettingsRepository, never()).save(any());
    }

    @Test
    void validateTokenRejectsWrongTokenAgainstHashRow() {
        when(apiSettingsRepository.findByApiTokenHash(ApiTokenHasher.sha256Hex("wrong-token")))
                .thenReturn(Optional.empty());
        when(apiSettingsRepository.findByApiToken("wrong-token")).thenReturn(Optional.empty());
        when(security.getMandat()).thenReturn("mandatA");
        when(apiSettingsRepository.findByMandat("mandatA")).thenReturn(Optional.empty());

        assertThat(service.validateToken("wrong-token")).isFalse();
    }

    @Test
    void validateTokenMigratesHashOnFirstPlaintextMatch() {
        // Alt-Zeile: nur Klartext, Hash noch leer -> beim ersten Match wird der Hash geschrieben.
        String token = "legacy-plaintext-token";
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setMandat("mandatA");
        settings.setApiToken(token);
        when(apiSettingsRepository.findByApiTokenHash(ApiTokenHasher.sha256Hex(token)))
                .thenReturn(Optional.empty());
        when(apiSettingsRepository.findByApiToken(token)).thenReturn(Optional.of(settings));

        assertThat(service.validateToken(token)).isTrue();

        ArgumentCaptor<AnforderungApiSettings> captor = ArgumentCaptor.forClass(AnforderungApiSettings.class);
        verify(apiSettingsRepository).save(captor.capture());
        assertThat(captor.getValue().getApiTokenHash()).isEqualTo(ApiTokenHasher.sha256Hex(token));
        // Verhalten fuer den aktiven Client identisch: Klartext bleibt (Uebergangsphase)
        assertThat(captor.getValue().getApiToken()).isEqualTo(token);
    }

    @Test
    void validateTokenSurvivesFailingHashMigration() {
        // Persistenz-Fehler bei der Lazy-Migration darf die Validierung NICHT brechen.
        String token = "legacy-plaintext-token";
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setMandat("mandatA");
        settings.setApiToken(token);
        when(apiSettingsRepository.findByApiTokenHash(ApiTokenHasher.sha256Hex(token)))
                .thenReturn(Optional.empty());
        when(apiSettingsRepository.findByApiToken(token)).thenReturn(Optional.of(settings));
        when(apiSettingsRepository.save(any())).thenThrow(new RuntimeException("DB down"));

        assertThat(service.validateToken(token)).isTrue();
    }

    @Test
    void validateTokenDoesNotResaveAlreadyMigratedRow() {
        // Zeile mit Klartext UND aktuellem Hash: Match ueber Hash-Lookup, kein erneutes save().
        String token = "migrated-token";
        String hash = ApiTokenHasher.sha256Hex(token);
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken(token);
        settings.setApiTokenHash(hash);
        when(apiSettingsRepository.findByApiTokenHash(hash)).thenReturn(Optional.of(settings));

        assertThat(service.validateToken(token)).isTrue();
        verify(apiSettingsRepository, never()).save(any());
    }

    @Test
    void mandatFallbackUsesHashComparisonWhenHashPresent() {
        String token = "fallback-token";
        when(apiSettingsRepository.findByApiTokenHash(ApiTokenHasher.sha256Hex(token)))
                .thenReturn(Optional.empty());
        when(apiSettingsRepository.findByApiToken(token)).thenReturn(Optional.empty());
        when(security.getMandat()).thenReturn("mandatA");
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiTokenHash(ApiTokenHasher.sha256Hex(token));
        when(apiSettingsRepository.findByMandat("mandatA")).thenReturn(Optional.of(settings));

        assertThat(service.validateToken(token)).isTrue();
    }

    @Test
    void mandatFallbackRejectsWhenHashDiffers() {
        String token = "fallback-token";
        when(apiSettingsRepository.findByApiTokenHash(ApiTokenHasher.sha256Hex(token)))
                .thenReturn(Optional.empty());
        when(apiSettingsRepository.findByApiToken(token)).thenReturn(Optional.empty());
        when(security.getMandat()).thenReturn("mandatA");
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken(token); // Klartext wuerde matchen ...
        settings.setApiTokenHash(ApiTokenHasher.sha256Hex("ANDERER-token")); // ... aber Hash gewinnt
        when(apiSettingsRepository.findByMandat("mandatA")).thenReturn(Optional.of(settings));

        assertThat(service.validateToken(token)).isFalse();
    }

    @Test
    void getMandatFromTokenWorksViaHashLookup() {
        String token = "hashed-token";
        String hash = ApiTokenHasher.sha256Hex(token);
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setMandat("mandatH");
        settings.setApiTokenHash(hash);
        when(apiSettingsRepository.findByApiTokenHash(hash)).thenReturn(Optional.of(settings));

        assertThat(service.getMandatFromToken(token)).contains("mandatH");
    }

    // --- getMandatFromToken ---

    @Test
    void getMandatFromTokenReturnsMandatWhenFound() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        settings.setMandat("mandatA");
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));

        assertThat(service.getMandatFromToken("token")).contains("mandatA");
    }

    @Test
    void getMandatFromTokenReturnsEmptyWhenNotFound() {
        when(apiSettingsRepository.findByApiToken("invalid")).thenReturn(Optional.empty());

        assertThat(service.getMandatFromToken("invalid")).isEmpty();
    }

    // --- getNextPromptNumber ---

    @Test
    void getNextPromptNumberReturns00001WhenNoExisting() {
        when(claudePromptRepository.findMaxPromptNumberByMandat("mandatA")).thenReturn(null);

        assertThat(service.getNextPromptNumber("mandatA")).isEqualTo("00001");
    }

    @Test
    void getNextPromptNumberIncrementsExisting() {
        when(claudePromptRepository.findMaxPromptNumberByMandat("mandatA")).thenReturn("00042");

        assertThat(service.getNextPromptNumber("mandatA")).isEqualTo("00043");
    }

    @Test
    void getNextPromptNumberHandlesInvalidFormat() {
        when(claudePromptRepository.findMaxPromptNumberByMandat("mandatA")).thenReturn("FEEDBACK-123-456");

        assertThat(service.getNextPromptNumber("mandatA")).isEqualTo("00001");
    }

    @Test
    void getNextPromptNumberPadsWithZeros() {
        when(claudePromptRepository.findMaxPromptNumberByMandat("mandatA")).thenReturn("00001");

        assertThat(service.getNextPromptNumber("mandatA")).isEqualTo("00002");
    }

    // --- getConstraintTemplateContent ---

    @Test
    void getConstraintTemplateContentReturnsEmptyWhenNoTemplateId() {
        Anforderung anf = new Anforderung();
        anf.setConstraintTemplateId(null);

        assertThat(service.getConstraintTemplateContent(anf)).isEmpty();
    }

    @Test
    void getConstraintTemplateContentReturnsContentWhenFound() {
        Anforderung anf = new Anforderung();
        anf.setConstraintTemplateId(1L);
        ConstraintTemplate template = new ConstraintTemplate();
        template.setConstraintsContent("Use Java 25");
        when(constraintTemplateRepository.findById(1L)).thenReturn(Optional.of(template));

        assertThat(service.getConstraintTemplateContent(anf)).isEqualTo("Use Java 25");
    }

    @Test
    void getConstraintTemplateContentReturnsEmptyWhenNotFound() {
        Anforderung anf = new Anforderung();
        anf.setConstraintTemplateId(999L);
        when(constraintTemplateRepository.findById(999L)).thenReturn(Optional.empty());

        assertThat(service.getConstraintTemplateContent(anf)).isEmpty();
    }

    // --- createPromptText ---

    @Test
    void createPromptTextReplacesPlaceholders() {
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setTitel("Fix Login");
        anf.setBeschreibung("Login broken");
        anf.setStatus("OFFEN");
        anf.setPriority("HOCH");

        String result = service.createPromptText(anf);

        assertThat(result).contains("Fix Login");
        assertThat(result).contains("Login broken");
        assertThat(result).contains("OFFEN");
        assertThat(result).contains("HOCH");
    }

    @Test
    void createPromptTextHandlesNullFields() {
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setTitel(null);
        anf.setBeschreibung(null);
        anf.setStatus(null);
        anf.setPriority(null);

        String result = service.createPromptText(anf);

        assertThat(result).isNotNull();
        assertThat(result).contains("Titel:");
    }

    // --- getHowtosForAnforderung ---

    @Test
    void getHowtosForAnforderungReturnsEmptyWhenNoIds() {
        Anforderung anf = new Anforderung();
        anf.setHowtoIds(null);

        assertThat(service.getHowtosForAnforderung(anf)).isEmpty();
    }

    @Test
    void getHowtosForAnforderungReturnsEmptyWhenBlank() {
        Anforderung anf = new Anforderung();
        anf.setHowtoIds("   ");

        assertThat(service.getHowtosForAnforderung(anf)).isEmpty();
    }

    @Test
    void getHowtosForAnforderungReturnsHowtos() {
        Anforderung anf = new Anforderung();
        anf.setHowtoIds("1,2,3");
        Howto h1 = new Howto();
        h1.setId(1L);
        Howto h2 = new Howto();
        h2.setId(2L);
        when(howtoRepository.findAllById(List.of(1L, 2L, 3L))).thenReturn(List.of(h1, h2));

        List<Howto> result = service.getHowtosForAnforderung(anf);
        assertThat(result).hasSize(2);
    }

    @Test
    void getHowtosForAnforderungHandlesInvalidIds() {
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setHowtoIds("abc,xyz");

        List<Howto> result = service.getHowtosForAnforderung(anf);
        assertThat(result).isEmpty();
    }

    @Test
    void getHowtosForAnforderungByIdReturnsEmptyWhenNotFound() {
        when(anforderungRepository.findById(999L)).thenReturn(Optional.empty());

        assertThat(service.getHowtosForAnforderung(999L)).isEmpty();
    }

    // --- createFullContext ---

    @Test
    void createFullContextIncludesAnforderungContent() {
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setTitel("Test");
        anf.setBeschreibung("Description");
        anf.setStatus("OFFEN");
        anf.setPriority("MITTEL");

        String context = service.createFullContext(anf);

        assertThat(context).contains("=== SPECIFIC ANFORDERUNG ===");
        assertThat(context).contains("Test");
    }

    @Test
    void createFullContextIncludesConstraintTemplate() {
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setTitel("Test");
        anf.setConstraintTemplateId(1L);
        ConstraintTemplate template = new ConstraintTemplate();
        template.setConstraintsContent("Must use Java 25");
        when(constraintTemplateRepository.findById(1L)).thenReturn(Optional.of(template));

        String context = service.createFullContext(anf);

        assertThat(context).contains("=== CONSTRAINT TEMPLATE ===");
        assertThat(context).contains("Must use Java 25");
    }

    @Test
    void createFullContextIncludesHowtos() {
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setTitel("Test");
        anf.setHowtoIds("1");
        Howto h = new Howto();
        h.setId(1L);
        h.setName("Build Guide");
        h.setText("Run mvn install");
        when(howtoRepository.findAllById(List.of(1L))).thenReturn(List.of(h));

        String context = service.createFullContext(anf);

        assertThat(context).contains("=== HOWTOS");
        assertThat(context).contains("Build Guide");
        assertThat(context).contains("Run mvn install");
    }

    @Test
    void createFullContextIncludesTargetModules() {
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setTitel("Test");
        anf.setTargetModules("plaintext-root-email, plaintext-root-jpa");

        String context = service.createFullContext(anf);

        assertThat(context).contains("Target Modules: plaintext-root-email, plaintext-root-jpa");
    }

    @Test
    void createFullContextIncludesBranchNamingPattern() {
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setTitel("Test");
        anf.setBranchNamingPattern("feature/{id}-{titel}");

        String context = service.createFullContext(anf);

        assertThat(context).contains("Branch Naming Pattern: feature/{id}-{titel}");
    }

    @Test
    void createFullContextIncludesUserAnswer() {
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setTitel("Test");
        anf.setUserAnswer("Please fix the CSS too");

        String context = service.createFullContext(anf);

        assertThat(context).contains("=== USER FEEDBACK FROM PREVIOUS ITERATION ===");
        assertThat(context).contains("Please fix the CSS too");
    }

    // --- createPrompt ---

    @Test
    void createPromptThrowsWhenAnforderungNotFound() {
        when(anforderungRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createPrompt(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void createPromptSavesNewPrompt() {
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setMandat("mandatA");
        anf.setTitel("Test");
        when(anforderungRepository.findById(1L)).thenReturn(Optional.of(anf));
        when(claudePromptRepository.findMaxPromptNumberByMandat("mandatA")).thenReturn(null);
        when(claudePromptRepository.save(any(ClaudePrompt.class))).thenAnswer(inv -> inv.getArgument(0));

        ClaudePrompt result = service.createPrompt(1L);

        assertThat(result.getPromptNumber()).isEqualTo("00001");
        assertThat(result.getAnforderungId()).isEqualTo(1L);
        assertThat(result.getMandat()).isEqualTo("mandatA");
        assertThat(result.getStatus()).isEqualTo("PENDING");
        assertThat(result.getRetryCount()).isZero();
    }

    // --- markPromptSent ---

    @Test
    void markPromptSentUpdatesStatus() {
        ClaudePrompt prompt = new ClaudePrompt();
        prompt.setPromptNumber("00001");
        prompt.setStatus("PENDING");
        when(claudePromptRepository.findByPromptNumber("00001")).thenReturn(Optional.of(prompt));
        when(claudePromptRepository.save(any(ClaudePrompt.class))).thenAnswer(inv -> inv.getArgument(0));

        service.markPromptSent("00001");

        ArgumentCaptor<ClaudePrompt> captor = ArgumentCaptor.forClass(ClaudePrompt.class);
        verify(claudePromptRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("SENT");
        assertThat(captor.getValue().getSentDate()).isNotNull();
        assertThat(captor.getValue().getTimeoutDate()).isNotNull();
    }

    @Test
    void markPromptSentDoesNothingWhenNotFound() {
        when(claudePromptRepository.findByPromptNumber("99999")).thenReturn(Optional.empty());

        service.markPromptSent("99999");

        verify(claudePromptRepository, never()).save(any());
    }

    // --- acknowledgePrompt ---

    @Test
    void acknowledgePromptReturnsFalseForInvalidToken() {
        when(apiSettingsRepository.findByApiToken("bad")).thenReturn(Optional.empty());
        when(security.getMandat()).thenReturn("MIGRATION");
        when(apiSettingsRepository.findByMandat("MIGRATION")).thenReturn(Optional.empty());

        assertThat(service.acknowledgePrompt("00001", "bad")).isFalse();
    }

    @Test
    void acknowledgePromptReturnsFalseWhenPromptNotFound() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));
        when(claudePromptRepository.findByPromptNumber("99999")).thenReturn(Optional.empty());

        assertThat(service.acknowledgePrompt("99999", "token")).isFalse();
    }

    @Test
    void acknowledgePromptUpdatesStatusAndAnforderung() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));

        ClaudePrompt prompt = new ClaudePrompt();
        prompt.setPromptNumber("00001");
        prompt.setAnforderungId(1L);
        when(claudePromptRepository.findByPromptNumber("00001")).thenReturn(Optional.of(prompt));
        when(claudePromptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Anforderung anf = new Anforderung();
        anf.setId(1L);
        when(anforderungRepository.findById(1L)).thenReturn(Optional.of(anf));
        when(anforderungRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean result = service.acknowledgePrompt("00001", "token");

        assertThat(result).isTrue();
        assertThat(prompt.getStatus()).isEqualTo("ACKNOWLEDGED");
        assertThat(anf.getLastExecutionDate()).isNotNull();
    }

    // --- getPromptStatus ---

    @Test
    void getPromptStatusReturnsStatusWhenFound() {
        ClaudePrompt prompt = new ClaudePrompt();
        prompt.setStatus("SENT");
        when(claudePromptRepository.findByPromptNumber("00001")).thenReturn(Optional.of(prompt));

        assertThat(service.getPromptStatus("00001")).isEqualTo("SENT");
    }

    @Test
    void getPromptStatusReturnsNotFoundWhenMissing() {
        when(claudePromptRepository.findByPromptNumber("99999")).thenReturn(Optional.empty());

        assertThat(service.getPromptStatus("99999")).isEqualTo("NOT_FOUND");
    }

    // --- isPromptAcknowledged ---

    @Test
    void isPromptAcknowledgedReturnsTrueWhenAcknowledged() {
        ClaudePrompt prompt = new ClaudePrompt();
        prompt.setStatus("ACKNOWLEDGED");
        when(claudePromptRepository.findByPromptNumber("00001")).thenReturn(Optional.of(prompt));

        assertThat(service.isPromptAcknowledged("00001")).isTrue();
    }

    @Test
    void isPromptAcknowledgedReturnsFalseWhenPending() {
        ClaudePrompt prompt = new ClaudePrompt();
        prompt.setStatus("PENDING");
        when(claudePromptRepository.findByPromptNumber("00001")).thenReturn(Optional.of(prompt));

        assertThat(service.isPromptAcknowledged("00001")).isFalse();
    }

    // --- isPromptTimedOut ---

    @Test
    void isPromptTimedOutReturnsFalseWhenNotFound() {
        when(claudePromptRepository.findByPromptNumber("99999")).thenReturn(Optional.empty());

        assertThat(service.isPromptTimedOut("99999")).isFalse();
    }

    @Test
    void isPromptTimedOutReturnsFalseWhenNotSent() {
        ClaudePrompt prompt = new ClaudePrompt();
        prompt.setStatus("PENDING");
        when(claudePromptRepository.findByPromptNumber("00001")).thenReturn(Optional.of(prompt));

        assertThat(service.isPromptTimedOut("00001")).isFalse();
    }

    @Test
    void isPromptTimedOutReturnsTrueWhenPastTimeout() {
        ClaudePrompt prompt = new ClaudePrompt();
        prompt.setStatus("SENT");
        prompt.setTimeoutDate(LocalDateTime.now().minusMinutes(1));
        when(claudePromptRepository.findByPromptNumber("00001")).thenReturn(Optional.of(prompt));

        assertThat(service.isPromptTimedOut("00001")).isTrue();
    }

    @Test
    void isPromptTimedOutReturnsFalseWhenBeforeTimeout() {
        ClaudePrompt prompt = new ClaudePrompt();
        prompt.setStatus("SENT");
        prompt.setTimeoutDate(LocalDateTime.now().plusMinutes(10));
        when(claudePromptRepository.findByPromptNumber("00001")).thenReturn(Optional.of(prompt));

        assertThat(service.isPromptTimedOut("00001")).isFalse();
    }

    // --- getNextTask ---

    @Test
    void getNextTaskReturnsEmptyForInvalidToken() {
        when(apiSettingsRepository.findByApiToken("bad")).thenReturn(Optional.empty());
        when(security.getMandat()).thenReturn("MIGRATION");
        when(apiSettingsRepository.findByMandat("MIGRATION")).thenReturn(Optional.empty());

        assertThat(service.getNextTask("bad")).isEmpty();
    }

    @Test
    void getNextTaskReturnsEmptyWhenAutomationDisabled() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        settings.setClaudeAutomationEnabled(false);
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));

        assertThat(service.getNextTask("token")).isEmpty();
    }

    @Test
    void getNextTaskReturnsHighestPriorityTask() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        settings.setMandat("mandatA");
        settings.setClaudeAutomationEnabled(true);
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));

        Anforderung low = new Anforderung();
        low.setId(1L);
        low.setStatus("OFFEN");
        low.setPriority("NIEDRIG");
        Anforderung high = new Anforderung();
        high.setId(2L);
        high.setStatus("OFFEN");
        high.setPriority("HOCH");
        Anforderung erledigt = new Anforderung();
        erledigt.setId(3L);
        erledigt.setStatus("ERLEDIGT");
        erledigt.setPriority("KRITISCH");

        when(anforderungRepository.findByMandatOrderByCreatedDateDesc("mandatA"))
                .thenReturn(new ArrayList<>(List.of(low, high, erledigt)));

        Optional<Anforderung> result = service.getNextTask("token");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(2L);
    }

    @Test
    void getNextTaskFiltersFeedbackStatus() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        settings.setMandat("mandatA");
        settings.setClaudeAutomationEnabled(true);
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));

        Anforderung feedback = new Anforderung();
        feedback.setId(1L);
        feedback.setStatus("FEEDBACK");
        feedback.setPriority("KRITISCH");
        Anforderung open = new Anforderung();
        open.setId(2L);
        open.setStatus("OFFEN");
        open.setPriority("NIEDRIG");

        when(anforderungRepository.findByMandatOrderByCreatedDateDesc("mandatA"))
                .thenReturn(new ArrayList<>(List.of(feedback, open)));

        Optional<Anforderung> result = service.getNextTask("token");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(2L);
    }

    // --- hasWork ---

    @Test
    void hasWorkReturnsFalseWhenNoTasks() {
        when(apiSettingsRepository.findByApiToken("bad")).thenReturn(Optional.empty());
        when(security.getMandat()).thenReturn("MIGRATION");
        when(apiSettingsRepository.findByMandat("MIGRATION")).thenReturn(Optional.empty());

        assertThat(service.hasWork("bad")).isFalse();
    }

    // --- retryPrompt ---

    @Test
    void retryPromptIncrementsRetryCountAndResetStatus() {
        ClaudePrompt prompt = new ClaudePrompt();
        prompt.setPromptNumber("00001");
        prompt.setStatus("TIMEOUT");
        prompt.setRetryCount(1);
        prompt.setTimeoutDate(LocalDateTime.now());
        when(claudePromptRepository.findByPromptNumber("00001")).thenReturn(Optional.of(prompt));
        when(claudePromptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.retryPrompt("00001");

        assertThat(prompt.getStatus()).isEqualTo("PENDING");
        assertThat(prompt.getRetryCount()).isEqualTo(2);
        assertThat(prompt.getTimeoutDate()).isNull();
    }

    @Test
    void retryPromptDoesNothingWhenNotFound() {
        when(claudePromptRepository.findByPromptNumber("99999")).thenReturn(Optional.empty());

        service.retryPrompt("99999");

        verify(claudePromptRepository, never()).save(any());
    }

    // --- handleTimeout ---

    @Test
    void handleTimeoutMarksAsTimeoutWhenUnderRetryLimit() {
        ClaudePrompt prompt = new ClaudePrompt();
        prompt.setPromptNumber("00001");
        prompt.setStatus("SENT");
        prompt.setRetryCount(1);
        when(claudePromptRepository.findByPromptNumber("00001")).thenReturn(Optional.of(prompt));
        when(claudePromptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handleTimeout("00001");

        assertThat(prompt.getStatus()).isEqualTo("TIMEOUT");
    }

    @Test
    void handleTimeoutMarksAsFailedAfterThreeRetries() {
        ClaudePrompt prompt = new ClaudePrompt();
        prompt.setPromptNumber("00001");
        prompt.setStatus("SENT");
        prompt.setRetryCount(3);
        when(claudePromptRepository.findByPromptNumber("00001")).thenReturn(Optional.of(prompt));
        when(claudePromptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.handleTimeout("00001");

        assertThat(prompt.getStatus()).isEqualTo("FAILED");
        assertThat(prompt.getErrorMessage()).contains("3 retries");
    }

    @Test
    void handleTimeoutDoesNothingWhenNotFound() {
        when(claudePromptRepository.findByPromptNumber("99999")).thenReturn(Optional.empty());

        service.handleTimeout("99999");

        verify(claudePromptRepository, never()).save(any());
    }

    // --- saveSummary ---

    @Test
    void saveSummaryReturnsFalseForInvalidToken() {
        when(apiSettingsRepository.findByApiToken("bad")).thenReturn(Optional.empty());
        when(security.getMandat()).thenReturn("MIGRATION");
        when(apiSettingsRepository.findByMandat("MIGRATION")).thenReturn(Optional.empty());

        assertThat(service.saveSummary("00001", "summary", "bad")).isFalse();
    }

    @Test
    void saveSummaryReturnsFalseWhenPromptNotFound() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));
        when(claudePromptRepository.findByPromptNumber("99999")).thenReturn(Optional.empty());

        assertThat(service.saveSummary("99999", "summary", "token")).isFalse();
    }

    @Test
    void saveSummaryCreatesFirstSummary() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));

        ClaudePrompt prompt = new ClaudePrompt();
        prompt.setAnforderungId(1L);
        when(claudePromptRepository.findByPromptNumber("00001")).thenReturn(Optional.of(prompt));

        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setClaudeSummary(null);
        when(anforderungRepository.findById(1L)).thenReturn(Optional.of(anf));
        when(anforderungRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean result = service.saveSummary("00001", "Work done", "token");

        assertThat(result).isTrue();
        assertThat(anf.getClaudeSummary()).contains("Work done");
        assertThat(anf.getClaudeSummary()).contains("Prompt: 00001");
    }

    @Test
    void saveSummaryAppendsToExisting() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));

        ClaudePrompt prompt = new ClaudePrompt();
        prompt.setAnforderungId(1L);
        when(claudePromptRepository.findByPromptNumber("00002")).thenReturn(Optional.of(prompt));

        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setClaudeSummary("### Previous summary\nOld content\n\n");
        when(anforderungRepository.findById(1L)).thenReturn(Optional.of(anf));
        when(anforderungRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.saveSummary("00002", "New work", "token");

        assertThat(anf.getClaudeSummary()).contains("Previous summary");
        assertThat(anf.getClaudeSummary()).contains("New work");
    }

    // --- updateAnforderungStatus ---

    @Test
    void updateAnforderungStatusReturnsFalseForInvalidToken() {
        when(apiSettingsRepository.findByApiToken("bad")).thenReturn(Optional.empty());
        when(security.getMandat()).thenReturn("MIGRATION");
        when(apiSettingsRepository.findByMandat("MIGRATION")).thenReturn(Optional.empty());

        assertThat(service.updateAnforderungStatus(1L, "OFFEN", "bad")).isFalse();
    }

    @Test
    void updateAnforderungStatusReturnsFalseForInvalidStatus() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));

        assertThat(service.updateAnforderungStatus(1L, "INVALID", "token")).isFalse();
    }

    @Test
    void updateAnforderungStatusUpdatesValidStatus() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        settings.setMandat("mandatA");
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));

        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setStatus("OFFEN");
        anf.setMandat("mandatA");
        when(anforderungRepository.findById(1L)).thenReturn(Optional.of(anf));
        when(anforderungRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        boolean result = service.updateAnforderungStatus(1L, "ERLEDIGT", "token");

        assertThat(result).isTrue();
        assertThat(anf.getStatus()).isEqualTo("ERLEDIGT");
    }

    @Test
    void updateAnforderungStatusDeniesForeignMandat() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        settings.setMandat("mandatA");
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));

        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setStatus("OFFEN");
        anf.setMandat("mandatB"); // fremder Mandant
        when(anforderungRepository.findById(1L)).thenReturn(Optional.of(anf));

        boolean result = service.updateAnforderungStatus(1L, "ERLEDIGT", "token");

        assertThat(result).isFalse();
        assertThat(anf.getStatus()).isEqualTo("OFFEN");
    }

    // --- createAnforderung ---

    @Test
    void createAnforderungReturnNullForInvalidToken() {
        when(apiSettingsRepository.findByApiToken("bad")).thenReturn(Optional.empty());
        when(security.getMandat()).thenReturn("MIGRATION");
        when(apiSettingsRepository.findByMandat("MIGRATION")).thenReturn(Optional.empty());

        assertThat(service.createAnforderung("bad", "title", "desc", null, null, null)).isNull();
    }

    @Test
    void createAnforderungReturnsNullForEmptyTitle() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        settings.setMandat("mandatA");
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));

        assertThat(service.createAnforderung("token", "", "desc", null, null, null)).isNull();
    }

    @Test
    void createAnforderungReturnsNullForNullTitle() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        settings.setMandat("mandatA");
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));

        assertThat(service.createAnforderung("token", null, "desc", null, null, null)).isNull();
    }

    @Test
    void createAnforderungDefaultsToMittelPriority() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        settings.setMandat("mandatA");
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));

        Anforderung saved = new Anforderung();
        saved.setId(42L);
        when(anforderungRepository.save(any())).thenReturn(saved);

        Long id = service.createAnforderung("token", "Test", null, null, null, null);

        assertThat(id).isEqualTo(42L);
        ArgumentCaptor<Anforderung> captor = ArgumentCaptor.forClass(Anforderung.class);
        verify(anforderungRepository).save(captor.capture());
        assertThat(captor.getValue().getPriority()).isEqualTo("MITTEL");
        assertThat(captor.getValue().getStatus()).isEqualTo("OFFEN");
        assertThat(captor.getValue().getErsteller()).isEqualTo("API");
    }

    @Test
    void createAnforderungUsesInvalidPriorityDefaultsToMittel() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        settings.setMandat("mandatA");
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));

        Anforderung saved = new Anforderung();
        saved.setId(1L);
        when(anforderungRepository.save(any())).thenReturn(saved);

        service.createAnforderung("token", "Test", null, "SUPER_HIGH", null, null);

        ArgumentCaptor<Anforderung> captor = ArgumentCaptor.forClass(Anforderung.class);
        verify(anforderungRepository).save(captor.capture());
        assertThat(captor.getValue().getPriority()).isEqualTo("MITTEL");
    }

    // --- getPromptsForAnforderung ---

    @Test
    void getPromptsForAnforderungDelegatesToRepository() {
        when(claudePromptRepository.findByAnforderungIdOrderByCreatedDateDesc(1L))
                .thenReturn(List.of(new ClaudePrompt()));

        assertThat(service.getPromptsForAnforderung(1L)).hasSize(1);
    }

    // --- getFullContextForAnforderung ---

    @Test
    void getFullContextForAnforderungReturnsNullForInvalidToken() {
        when(apiSettingsRepository.findByApiToken("bad")).thenReturn(Optional.empty());
        when(security.getMandat()).thenReturn("MIGRATION");
        when(apiSettingsRepository.findByMandat("MIGRATION")).thenReturn(Optional.empty());

        assertThat(service.getFullContextForAnforderung(1L, "bad")).isNull();
    }

    @Test
    void getFullContextForAnforderungReturnsNullWhenNotFound() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(settings));
        when(anforderungRepository.findById(999L)).thenReturn(Optional.empty());

        assertThat(service.getFullContextForAnforderung(999L, "token")).isNull();
    }

    // --- Karte 968: wiederkehrende Aufgaben (java:S6809) ---

    private static AnforderungApiSettings aktiveEinstellungen() {
        AnforderungApiSettings settings = new AnforderungApiSettings();
        settings.setApiToken("token");
        settings.setMandat("mandatA");
        settings.setClaudeAutomationEnabled(true);
        return settings;
    }

    private static Anforderung wiederkehrend(long id, int tage, LocalDateTime zuletzt) {
        Anforderung a = new Anforderung();
        a.setId(id);
        a.setStatus("ERLEDIGT");
        a.setPriority("MITTEL");
        a.setWiederkehrend(true);
        a.setWiederkehrendTage(tage);
        a.setLastModifiedDate(zuletzt);
        return a;
    }

    /**
     * Das Wiedereroeffnen war bis Karte 968 durch keinen Test gedeckt — und trug ein
     * {@code @Transactional}, das an einer privaten Methode ohnehin wirkungslos war. Beim Entfernen
     * der Annotation musste belegt sein, <b>was</b> die Methode tut, sonst waere es eine
     * Aenderung ins Blaue gewesen.
     */
    @Test
    void ueberfaelligeWiederkehrendeAufgabeWirdWiederEroeffnetUndSofortAusgeliefert() {
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(aktiveEinstellungen()));
        Anforderung ueberfaellig = wiederkehrend(10L, 7, LocalDateTime.now().minusDays(8));
        when(anforderungRepository.findByMandatOrderByCreatedDateDesc("mandatA"))
                .thenReturn(new ArrayList<>(List.of(ueberfaellig)));

        Optional<Anforderung> ergebnis = service.getNextTask("token");

        ArgumentCaptor<Anforderung> gespeichert = ArgumentCaptor.forClass(Anforderung.class);
        verify(anforderungRepository).save(gespeichert.capture());
        assertThat(gespeichert.getValue().getStatus()).isEqualTo("OFFEN");
        // Und sie kommt im selben Aufruf zurueck - der Filter arbeitet auf derselben Instanz.
        assertThat(ergebnis).isPresent();
        assertThat(ergebnis.get().getId()).isEqualTo(10L);
    }

    @Test
    void nochNichtFaelligeWiederkehrendeAufgabeBleibtErledigt() {
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(aktiveEinstellungen()));
        Anforderung frisch = wiederkehrend(11L, 7, LocalDateTime.now().minusDays(2));
        when(anforderungRepository.findByMandatOrderByCreatedDateDesc("mandatA"))
                .thenReturn(new ArrayList<>(List.of(frisch)));

        // Gegenprobe zum Fall darueber: ohne sie waere ein Dienst, der ALLES wiedereroeffnet,
        // ebenfalls gruen.
        assertThat(service.getNextTask("token")).isEmpty();
        verify(anforderungRepository, never()).save(any());
        assertThat(frisch.getStatus()).isEqualTo("ERLEDIGT");
    }

    @Test
    void eineFehlgeschlageneAufgabeHaeltDieUebrigenNichtAuf() {
        // Jeder save() laeuft in seiner eigenen Transaktion - genau das steht seit Karte 968 auch
        // im Javadoc der Methode. Bricht einer weg, muss der naechste Aufruf den Rest nachholen
        // koennen; ein halb durchgelaufener Lauf darf nicht in sich zusammenfallen.
        when(apiSettingsRepository.findByApiToken("token")).thenReturn(Optional.of(aktiveEinstellungen()));
        Anforderung a = wiederkehrend(20L, 3, LocalDateTime.now().minusDays(9));
        when(anforderungRepository.findByMandatOrderByCreatedDateDesc("mandatA"))
                .thenReturn(new ArrayList<>(List.of(a)));
        when(anforderungRepository.save(any())).thenThrow(new RuntimeException("DB weg"));

        assertThatThrownBy(() -> service.getNextTask("token")).isInstanceOf(RuntimeException.class);

        // Der Status im Speicher steht bereits auf OFFEN - in der DB nicht. Beim naechsten Lauf
        // wird die Aufgabe erneut als ueberfaellig erkannt und noch einmal versucht.
        assertThat(a.getStatus()).isEqualTo("OFFEN");
    }
}
