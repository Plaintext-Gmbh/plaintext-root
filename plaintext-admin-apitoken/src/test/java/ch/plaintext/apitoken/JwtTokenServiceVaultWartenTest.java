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
 * Karte 632: Beim Start auf einen vorübergehend nicht erreichbaren Vault warten, statt zu sterben.
 *
 * <p><b>Der Fall, den das verhindert:</b> Vaultwarden riegelt mit HTTP 429 ab, der Schlüssel kommt
 * nicht, die Anwendung stirbt — und {@code restart: always} startet sie sofort neu, wo sie erneut
 * anklopft und die Sperre verlängert. Am 08.08.2026 kam {@code plaintext-app-prod-blue} so auf
 * {@code RestartCount 11} und war eine Stunde nicht erreichbar.</p>
 *
 * <p>Die Tests setzen die Wartezeit auf 0 s, damit sie nicht real schlafen — geprüft wird die
 * <b>Entscheidung</b>, ob wiederholt wird, nicht die Dauer.</p>
 */
class JwtTokenServiceVaultWartenTest {

    private static final String VAULT_ITEM = "plaintext.jwt-signing-key";
    private static final String FELD = "private_key_pem";

    /** Baut einen PEM-Block, wie ihn der Vault liefert. */
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
                // Die ersten beiden Anläufe laufen ins Leere — so verhält sich der Client während
                // der 429-Sperre: Er klopft gar nicht erst an und liefert leer zurück.
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
        // Genau drei Versuche, dann Schluss — kein endloses Warten, das den Container haengen liesse.
        verify(vault, org.mockito.Mockito.times(3)).getField(any(), any());
    }

    @Test
    @DisplayName("Ohne konfigurierten Vault wird NICHT gewartet — der Startfehler kommt sofort")
    void ohneVaultKonfigurationSofortScheitern() {
        VaultwardenSecretService vault = mock(VaultwardenSecretService.class);
        JwtTokenService service = service(vault, 8);
        // Kein Vault-Item: Warten wuerde den unvermeidlichen Fehler nur verzoegern.
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
        // isEnabled()==false -> gar kein Feldzugriff, und kein zweiter Anlauf.
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
