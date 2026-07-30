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
 * Unit-Tests fuer {@link VaultwardenValueResolver}: Aufloesung von Passwort / Username /
 * Custom-Feld, Fail-fast bei deaktiviertem Vault bzw. fehlendem Item, Namenskonventions-WARN,
 * Item-Namen mit Leerzeichen und das Caching (ein Sync reicht). Der {@link VaultwardenSecretService}
 * wird gemockt.
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

    // ── Aufloesung der drei Syntaxformen ──────────────────────────────────────

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

    // ── Item-Namen mit Leerzeichen ────────────────────────────────────────────

    @Test
    void itemNameMitLeerzeichenBleibtErhalten() {
        when(svc.getUsername("my login item")).thenReturn(Optional.of("admin"));
        assertThat(resolver.resolve("some.prop", "vault:my login item#username")).isEqualTo("admin");
        // Name folgt nicht app.key -> WARN, aber trotzdem aufgeloest.
        assertThat(warnedAboutConvention()).isTrue();
        verify(svc).getUsername("my login item");
    }

    // ── Namenskonvention app.key ──────────────────────────────────────────────

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
        // Selbst wenn der Wert existierte: die Meldung darf nur Property- und Item-Namen nennen.
        assertThatThrownBy(() -> resolver.resolve("zeit.token", "vault:app.geheim"))
                .isInstanceOf(VaultwardenPropertyResolutionException.class)
                .hasMessageContaining("zeit.token")
                .hasMessageContaining("app.geheim")
                .hasMessageNotContaining("MASTER")
                .hasMessageNotContaining("password=");
    }

    // ── Caching: ein Sync reicht ──────────────────────────────────────────────

    @Test
    void aufgeloesterWertWirdGecacht() {
        when(svc.getPassword("app.jira-bit-admin")).thenReturn(Optional.of("geheim"));
        String raw = "vault:app.jira-bit-admin";
        assertThat(resolver.resolve("p", raw)).isEqualTo("geheim");
        assertThat(resolver.resolve("p", raw)).isEqualTo("geheim");
        // Zweiter Zugriff aus dem Cache -> Service nur einmal befragt.
        verify(svc, times(1)).getPassword("app.jira-bit-admin");
    }

    @Test
    void serviceWirdLazyNurEinmalBezogen() {
        // Supplier zaehlt Zugriffe; erst beim ersten vault:-Wert, danach gecacht.
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

    @Test
    void isVaultReferenceErkenntPrefix() {
        assertThat(VaultwardenValueResolver.isVaultReference("vault:app.x")).isTrue();
        assertThat(VaultwardenValueResolver.isVaultReference("plain")).isFalse();
        assertThat(VaultwardenValueResolver.isVaultReference(42)).isFalse();
        assertThat(VaultwardenValueResolver.isVaultReference(null)).isFalse();
    }
}
