package ch.plaintext.apitoken;

import ch.plaintext.boot.plugins.secret.VaultwardenSecretService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Card 632: At startup, wait for a temporarily unreachable vault instead of dying.
 *
 * <p><b>The case this prevents:</b> Vaultwarden locks down with HTTP 429, the key does not
 * arrive, the application dies — and {@code restart: always} restarts it immediately, whereupon it
 * knocks again and extends the lockout. On 08.08.2026 {@code plaintext-app-prod-blue} reached
 * {@code RestartCount 11} this way and was unreachable for an hour.</p>
 *
 * <p>The tests set the wait time to 0 s so that they do not really sleep — what is checked is the
 * <b>decision</b> whether to retry, not the duration.</p>
 */
class JwtTokenServiceVaultWartenTest {

    private static final String VAULT_ITEM = "plaintext.jwt-signing-key";
    private static final String FELD = "private_key_pem";

    /** Builds a PEM block the way the vault delivers it. */
    private static String pemBlock(String typ, byte[] der) {
        return "-----BEGIN " + typ + "-----\n"
                + java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + typ + "-----\n";
    }

    @SuppressWarnings("unchecked")
    private JwtTokenService service(VaultwardenSecretService vault, int versuche) {
        ObjectProvider<VaultwardenSecretService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(vault);
        JwtTokenService service = new JwtTokenService(provider);
        ReflectionTestUtils.setField(service, "privateKeyVaultItem", VAULT_ITEM);
        ReflectionTestUtils.setField(service, "activeProfiles", "prod");
        service.vaultWaitAttempts = versuche;
        service.vaultWaitSeconds = 0;
        return service;
    }

    @Test
    @DisplayName("Vault liefert erst beim dritten Versuch — die Anwendung startet trotzdem")
    void wartetAufDenVaultUndKommtHoch() throws Exception {
        java.security.KeyPairGenerator gen = java.security.KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        java.security.KeyPair paar = gen.generateKeyPair();
        String privatPem = pemBlock("PRIVATE KEY", paar.getPrivate().getEncoded());
        String publicPem = pemBlock("PUBLIC KEY", paar.getPublic().getEncoded());

        AtomicInteger privatAbrufe = new AtomicInteger();
        VaultwardenSecretService vault = mock(VaultwardenSecretService.class);
        when(vault.isEnabled()).thenReturn(true);
        when(vault.getField(any(), any())).thenAnswer(a -> {
            String feld = a.getArgument(1);
            if (FELD.equals(feld)) {
                // The first two attempts come up empty — that is how the client behaves during
                // the 429 lockout: it does not even knock and returns empty.
                return privatAbrufe.incrementAndGet() < 3 ? Optional.empty() : Optional.of(privatPem);
            }
            return Optional.of(publicPem);
        });

        JwtTokenService service = service(vault, 8);
        service.init();

        assertEquals(3, privatAbrufe.get(), "erst der dritte Versuch liefert den Schlüssel");
    }

    @Test
    @DisplayName("Bleibt der Vault stumm, scheitert der Start — fail-closed bleibt fail-closed")
    void gibtNachDenVersuchenAufStattEinenErsatzschluesselZuNehmen() {
        VaultwardenSecretService vault = mock(VaultwardenSecretService.class);
        when(vault.isEnabled()).thenReturn(true);
        when(vault.getField(any(), any())).thenReturn(Optional.empty());

        JwtTokenService service = service(vault, 3);

        IllegalStateException e = assertThrows(IllegalStateException.class, service::init);
        assertTrue(e.getMessage().contains("Cannot initialize JWT service without RSA keys"));
        // Exactly three attempts, then stop — no endless waiting that would leave the container hanging.
        verify(vault, org.mockito.Mockito.times(3)).getField(any(), any());
    }

    @Test
    @DisplayName("Ohne konfigurierten Vault wird NICHT gewartet — der Startfehler kommt sofort")
    void ohneVaultKonfigurationSofortScheitern() {
        VaultwardenSecretService vault = mock(VaultwardenSecretService.class);
        JwtTokenService service = service(vault, 8);
        // No vault item: waiting would only delay the inevitable error.
        ReflectionTestUtils.setField(service, "privateKeyVaultItem", "");

        assertThrows(IllegalStateException.class, service::init);
        verify(vault, never()).getField(any(), any());
    }

    @Test
    @DisplayName("Deaktivierter Vault wird nicht abgewartet")
    void deaktivierterVaultWirdNichtAbgewartet() {
        VaultwardenSecretService vault = mock(VaultwardenSecretService.class);
        when(vault.isEnabled()).thenReturn(false);

        JwtTokenService service = service(vault, 8);

        assertThrows(IllegalStateException.class, service::init);
        // isEnabled()==false -> no field access at all, and no second attempt.
        verify(vault, never()).getField(any(), any());
    }

    @Test
    @DisplayName("vault-wait-attempts=0 verhält sich wie vor Karte 632: genau ein Versuch")
    void abschaltbarAufDasAlteVerhalten() {
        VaultwardenSecretService vault = mock(VaultwardenSecretService.class);
        when(vault.isEnabled()).thenReturn(true);
        when(vault.getField(any(), any())).thenReturn(Optional.empty());

        JwtTokenService service = service(vault, 0);

        assertThrows(IllegalStateException.class, service::init);
        verify(vault, org.mockito.Mockito.times(1)).getField(any(), any());
    }
}
