/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Tests fuer {@link VaultwardenPropertySource} im echten {@link StandardEnvironment}:
 * Durchfall normaler Werte, transparente Aufloesung von {@code vault:}-Werten und der
 * Re-Entranz-Schutz (die Source liest waehrend einer Aufloesung nicht rekursiv erneut auf).
 */
class VaultwardenPropertySourceTest {

    private StandardEnvironment environment;
    private Map<String, Object> map;

    @BeforeEach
    void setUp() {
        environment = new StandardEnvironment();
        map = new HashMap<>();
        map.put("plain.value", "hello");
        map.put("app.secret", "vault:app.jira-bit-admin");
        map.put("plaintext.vault.email", "service@plaintext.ch");
        environment.getPropertySources().addLast(new MapPropertySource("test", map));
    }

    private VaultwardenSecretService enabledService() {
        VaultwardenSecretService svc = mock(VaultwardenSecretService.class);
        when(svc.isEnabled()).thenReturn(true);
        when(svc.getPassword("app.jira-bit-admin")).thenReturn(Optional.of("geheim"));
        return svc;
    }

    private void install(VaultwardenValueResolver resolver) {
        environment.getPropertySources().addFirst(new VaultwardenPropertySource(environment, resolver));
    }

    // ── Durchfall normaler Werte ──────────────────────────────────────────────

    @Test
    void normalerWertFaelltDurch() {
        AtomicInteger supplierCalls = new AtomicInteger();
        install(new VaultwardenValueResolver(() -> {
            supplierCalls.incrementAndGet();
            return enabledService();
        }));
        assertThat(environment.getProperty("plain.value")).isEqualTo("hello");
        // Ein normaler Wert darf den Vault-Client nicht instanziieren.
        assertThat(supplierCalls.get()).isZero();
    }

    // ── Transparente Aufloesung ───────────────────────────────────────────────

    @Test
    void vaultWertWirdTransparentAufgeloest() {
        install(new VaultwardenValueResolver(this::enabledServiceSupplier));
        assertThat(environment.getProperty("app.secret")).isEqualTo("geheim");
    }

    private VaultwardenSecretService enabledServiceSupplier() {
        return enabledService();
    }

    // ── Re-Entranz-Schutz ─────────────────────────────────────────────────────

    @Test
    void reEntranterZugriffWaehrendClientInitFuehrtNichtInRekursion() {
        AtomicInteger supplierCalls = new AtomicInteger();
        // Der Supplier liest waehrend der (lazy) Client-Init selbst wieder aus dem
        // Environment -> muss dank ThreadLocal-Guard OHNE Rekursion durchlaufen.
        VaultwardenValueResolver resolver = new VaultwardenValueResolver(() -> {
            supplierCalls.incrementAndGet();
            String bootstrapEmail = environment.getProperty("plaintext.vault.email");
            assertThat(bootstrapEmail).isEqualTo("service@plaintext.ch");
            return enabledService();
        });
        install(resolver);

        assertThat(environment.getProperty("app.secret")).isEqualTo("geheim");
        assertThat(supplierCalls.get()).isEqualTo(1);
    }

    // ── containsProperty loest nicht auf ──────────────────────────────────────

    @Test
    void containsPropertyLoestNichtAus() {
        AtomicInteger supplierCalls = new AtomicInteger();
        install(new VaultwardenValueResolver(() -> {
            supplierCalls.incrementAndGet();
            return enabledService();
        }));
        // Existenz wird von der echten Source beantwortet, ohne den Vault zu befragen.
        assertThat(environment.containsProperty("app.secret")).isTrue();
        assertThat(supplierCalls.get()).isZero();
    }

    // ── Fail-fast propagiert bis zum Environment-Zugriff ──────────────────────

    @Test
    void nichtAufloesbarerWertBrichtZugriffAb() {
        VaultwardenSecretService svc = mock(VaultwardenSecretService.class);
        when(svc.isEnabled()).thenReturn(true);
        when(svc.getPassword("app.jira-bit-admin")).thenReturn(Optional.empty());
        install(new VaultwardenValueResolver(() -> svc));

        assertThatThrownBy(() -> environment.getProperty("app.secret"))
                .isInstanceOf(VaultwardenPropertyResolutionException.class)
                .hasMessageContaining("app.secret")
                .hasMessageContaining("app.jira-bit-admin");
    }
}
