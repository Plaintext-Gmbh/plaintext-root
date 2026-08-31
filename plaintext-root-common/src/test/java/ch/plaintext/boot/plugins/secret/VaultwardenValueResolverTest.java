/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Unit tests for {@link VaultwardenValueResolver}: resolution of password / username /
 * custom field, fail-fast on a disabled vault resp. a missing item, the naming convention WARN,
 * item names with spaces and the caching (one sync is enough). The
 * {@link VaultwardenSecretService} is mocked.
 */
class VaultwardenValueResolverTest {

    private VaultwardenSecretService svc;
    private VaultwardenValueResolver resolver;
    private ListAppender<ILoggingEvent> logs;
    private Logger resolverLogger;

    @BeforeEach
    void setUp() {
        svc = mock(VaultwardenSecretService.class);
        when(svc.isEnabled()).thenReturn(true);
        resolver = new VaultwardenValueResolver(() -> svc);

        resolverLogger = (Logger) LoggerFactory.getLogger(VaultwardenValueResolver.class);
        logs = new ListAppender<>();
        logs.start();
        resolverLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        resolverLogger.detachAppender(logs);
    }

    private boolean warnedAboutConvention() {
        return logs.list.stream()
                .anyMatch(e -> e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("Namenskonvention app.key"));
    }

    // ── Resolution of the three syntax forms ──────────────────────────────────

    @Test
    void passwortWirdAufgeloest() {
        when(svc.getPassword("app.jira-bit-admin")).thenReturn(Optional.of("geheim"));
        assertThat(resolver.resolve("zeit.jira.password", "vault:app.jira-bit-admin")).isEqualTo("geheim");
        assertThat(warnedAboutConvention()).isFalse();
    }

    @Test
    void usernameWirdAufgeloest() {
        when(svc.getUsername("app.jira-bit-admin")).thenReturn(Optional.of("svc-user"));
        assertThat(resolver.resolve("zeit.jira.user", "vault:app.jira-bit-admin#username"))
                .isEqualTo("svc-user");
    }

    @Test
    void customFeldWirdAufgeloest() {
        when(svc.getField("app.sciforma", "api-key")).thenReturn(Optional.of("tok-42"));
        assertThat(resolver.resolve("zeit.sciforma.key", "vault:app.sciforma#field:api-key"))
                .isEqualTo("tok-42");
    }

    @Test
    void expliziterPasswordSelektorWieDefault() {
        when(svc.getPassword("app.sciforma")).thenReturn(Optional.of("pw"));
        assertThat(resolver.resolve("p", "vault:app.sciforma#password")).isEqualTo("pw");
    }

    // ── Item names with spaces ────────────────────────────────────────────────

    @Test
    void itemNameMitLeerzeichenBleibtErhalten() {
        when(svc.getUsername("my login item")).thenReturn(Optional.of("admin"));
        assertThat(resolver.resolve("some.prop", "vault:my login item#username")).isEqualTo("admin");
        // The name does not follow app.key -> WARN, but resolved nonetheless.
        assertThat(warnedAboutConvention()).isTrue();
        verify(svc).getUsername("my login item");
    }

    // ── Naming convention app.key ─────────────────────────────────────────────

    @Test
    void nichtKonformerNameWarntAberLoestAuf() {
        when(svc.getPassword("KaputterName")).thenReturn(Optional.of("x"));
        assertThat(resolver.resolve("p", "vault:KaputterName")).isEqualTo("x");
        assertThat(warnedAboutConvention()).isTrue();
    }

    @Test
    void konformerNameWarntNicht() {
        when(svc.getPassword("guild.paperless-token")).thenReturn(Optional.of("x"));
        resolver.resolve("p", "vault:guild.paperless-token");
        assertThat(warnedAboutConvention()).isFalse();
    }

    // ── Fail-fast ─────────────────────────────────────────────────────────────

    @Test
    void deaktivierterVaultFuehrtZuFailFast() {
        when(svc.isEnabled()).thenReturn(false);
        assertThatThrownBy(() -> resolver.resolve("zeit.jira.password", "vault:app.jira-bit-admin"))
                .isInstanceOf(VaultwardenPropertyResolutionException.class)
                .hasMessageContaining("zeit.jira.password")
                .hasMessageContaining("app.jira-bit-admin")
                .hasMessageContaining("deaktiviert");
    }

    @Test
    void fehlendesItemFuehrtZuFailFast() {
        when(svc.getPassword("app.fehlt")).thenReturn(Optional.empty());
        VaultwardenPropertyResolutionException ex = catchThrowableOfType(
                VaultwardenPropertyResolutionException.class,
                () -> resolver.resolve("zeit.token", "vault:app.fehlt"));
        assertThat(ex).isNotNull();
        assertThat(ex.getPropertyName()).isEqualTo("zeit.token");
        assertThat(ex.getItemName()).isEqualTo("app.fehlt");
    }

    @Test
    void unbekannterSelektorFuehrtZuFailFast() {
        assertThatThrownBy(() -> resolver.resolve("p", "vault:app.key#quatsch"))
                .isInstanceOf(VaultwardenPropertyResolutionException.class)
                .hasMessageContaining("Selektor");
    }

    @Test
    void failFastMeldungEnthaeltKeinSecret() {
        when(svc.getPassword("app.geheim")).thenReturn(Optional.empty());
        // Even if the value existed: the message may name only property and item names.
        assertThatThrownBy(() -> resolver.resolve("zeit.token", "vault:app.geheim"))
                .isInstanceOf(VaultwardenPropertyResolutionException.class)
                .hasMessageContaining("zeit.token")
                .hasMessageContaining("app.geheim")
                .hasMessageNotContaining("MASTER")
                .hasMessageNotContaining("password=");
    }

    // ── Caching: one sync is enough ───────────────────────────────────────────

    @Test
    void aufgeloesterWertWirdGecacht() {
        when(svc.getPassword("app.jira-bit-admin")).thenReturn(Optional.of("geheim"));
        String raw = "vault:app.jira-bit-admin";
        assertThat(resolver.resolve("p", raw)).isEqualTo("geheim");
        assertThat(resolver.resolve("p", raw)).isEqualTo("geheim");
        // Second access from the cache -> the service is asked only once.
        verify(svc, times(1)).getPassword("app.jira-bit-admin");
    }

    @Test
    void serviceWirdLazyNurEinmalBezogen() {
        // The supplier counts accesses; only on the first vault: value, cached afterwards.
        int[] calls = {0};
        VaultwardenValueResolver lazy = new VaultwardenValueResolver(() -> {
            calls[0]++;
            VaultwardenSecretService s = mock(VaultwardenSecretService.class);
            when(s.isEnabled()).thenReturn(true);
            when(s.getPassword("app.a")).thenReturn(Optional.of("a"));
            when(s.getPassword("app.b")).thenReturn(Optional.of("b"));
            return s;
        });
        assertThat(calls[0]).isZero();
        lazy.resolve("p1", "vault:app.a");
        lazy.resolve("p2", "vault:app.b");
        assertThat(calls[0]).isEqualTo(1);
    }

    // ── Boot retry on a transient vault disturbance (incidents 18.+21.08.2026) ─

    /** Recorder instead of real time: collects the requested waiting times. */
    private List<Long> schlaefe;

    private VaultwardenValueResolver resolverMitSchlafRekorder() {
        schlaefe = new ArrayList<>();
        return new VaultwardenValueResolver(() -> svc, ms -> schlaefe.add(ms));
    }

    @Test
    void transienteStoerungWirdMitRetryUeberbrueckt() {
        // First attempt empty (the vault access failed), the second returns the value.
        when(svc.getPassword("app.wackel")).thenReturn(Optional.empty(), Optional.of("pw"));
        when(svc.istLetzterZugriffTransientGescheitert()).thenReturn(true);
        when(svc.warLetzterFehlerRateLimit()).thenReturn(false);
        when(svc.letzteVaultFehlermeldung()).thenReturn("connect timed out");
        VaultwardenValueResolver r = resolverMitSchlafRekorder();

        assertThat(r.resolve("p", "vault:app.wackel")).isEqualTo("pw");
        assertThat(schlaefe).containsExactly(5_000L);
        verify(svc, times(1)).erzwingeNeuenVersuch();
    }

    @Test
    void transienteStoerungOhneEndeFailtNachAllenVersuchenMitVerdoppelterWartezeit() {
        when(svc.getPassword("app.wackel")).thenReturn(Optional.empty());
        when(svc.istLetzterZugriffTransientGescheitert()).thenReturn(true);
        when(svc.warLetzterFehlerRateLimit()).thenReturn(false);
        when(svc.letzteVaultFehlermeldung()).thenReturn("connect timed out");
        VaultwardenValueResolver r = resolverMitSchlafRekorder();

        assertThatThrownBy(() -> r.resolve("p", "vault:app.wackel"))
                .isInstanceOf(VaultwardenPropertyResolutionException.class)
                .hasMessageContaining("transient")
                .hasMessageContaining("connect timed out");
        assertThat(schlaefe).containsExactly(5_000L, 10_000L, 20_000L);
    }

    @Test
    void rateLimitWartetLangUndNenntDen429ImFehler() {
        // The deploy retry (plaintext-scripts) recognizes the transience by the string "HTTP 429" in
        // the container log — the fail-fast message therefore has to carry it.
        when(svc.getPassword("app.wackel")).thenReturn(Optional.empty());
        when(svc.istLetzterZugriffTransientGescheitert()).thenReturn(true);
        when(svc.warLetzterFehlerRateLimit()).thenReturn(true);
        when(svc.letzteVaultFehlermeldung())
                .thenReturn("token-Endpoint HTTP 429 ({\"message\":\"Too many login requests\"})");
        VaultwardenValueResolver r = resolverMitSchlafRekorder();

        assertThatThrownBy(() -> r.resolve("p", "vault:app.wackel"))
                .isInstanceOf(VaultwardenPropertyResolutionException.class)
                .hasMessageContaining("HTTP 429");
        assertThat(schlaefe).containsExactly(65_000L, 65_000L, 65_000L);
    }

    @Test
    void definitivFehlendesItemFailtSofortOhneRetry() {
        // The sync succeeded, the item really is missing (the typo case schuetu.remember-me-keyn
        // of 18.08.2026): no waiting, no further attempts — the boot aborts immediately.
        when(svc.getPassword("app.fehlt")).thenReturn(Optional.empty());
        when(svc.istLetzterZugriffTransientGescheitert()).thenReturn(false);
        VaultwardenValueResolver r = resolverMitSchlafRekorder();

        assertThatThrownBy(() -> r.resolve("p", "vault:app.fehlt"))
                .isInstanceOf(VaultwardenPropertyResolutionException.class)
                .hasMessageContaining("nicht im Tresor gefunden");
        assertThat(schlaefe).isEmpty();
        verify(svc, times(0)).erzwingeNeuenVersuch();
    }

    @Test
    void unterbrochenerRetryFailtFastUndSetztDasInterruptFlag() {
        when(svc.getPassword("app.wackel")).thenReturn(Optional.empty());
        when(svc.istLetzterZugriffTransientGescheitert()).thenReturn(true);
        when(svc.warLetzterFehlerRateLimit()).thenReturn(false);
        when(svc.letzteVaultFehlermeldung()).thenReturn("timeout");
        VaultwardenValueResolver r = new VaultwardenValueResolver(() -> svc, ms -> {
            throw new InterruptedException("stop");
        });

        try {
            assertThatThrownBy(() -> r.resolve("p", "vault:app.wackel"))
                    .isInstanceOf(VaultwardenPropertyResolutionException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted(); // clear the flag so that subsequent tests run cleanly
        }
    }

    @Test
    void isVaultReferenceErkenntPrefix() {
        assertThat(VaultwardenValueResolver.isVaultReference("vault:app.x")).isTrue();
        assertThat(VaultwardenValueResolver.isVaultReference("plain")).isFalse();
        assertThat(VaultwardenValueResolver.isVaultReference(42)).isFalse();
        assertThat(VaultwardenValueResolver.isVaultReference(null)).isFalse();
    }
}
