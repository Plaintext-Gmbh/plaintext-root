package ch.plaintext.apitoken;

import ch.plaintext.boot.plugins.secret.VaultwardenSecretService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Card 1025: a PROD instance must be able to run WITHOUT Vaultwarden.
 *
 * <p><b>What this is about.</b> All eight application containers carry the same vault account with
 * the master password of the entire vault — whoever gets into one application holds the key to the
 * secrets of all of them. Getting that key out of a container failed on this service: the private
 * key already had a file alternative ({@code plaintext.jwt.private-key-file}), the public one did
 * not — {@code loadPublicKeys()} threw as soon as {@code private-key-vault-item} was empty, and
 * INT shares the {@code prod} profile. A public key is not a secret and does not need a vault.</p>
 *
 * <p>What must NOT change is the fail-closed line of card 347: no classpath fallback in PROD, and
 * never a key that does not belong to the private one — that mismatch was bug 347 itself.</p>
 */
class JwtTokenServiceSchluesselAusDateiTest {

    private static String pem(String typ, byte[] der) {
        return "-----BEGIN " + typ + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(der)
                + "\n-----END " + typ + "-----\n";
    }

    private static KeyPair paar() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    @SuppressWarnings("unchecked")
    private JwtTokenService prodDienst(VaultwardenSecretService vault) {
        ObjectProvider<VaultwardenSecretService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(vault);
        JwtTokenService service = new JwtTokenService(provider);
        ReflectionTestUtils.setField(service, "activeProfiles", "prod");
        service.vaultWaitAttempts = 1;
        service.vaultWaitSeconds = 0;
        return service;
    }

    @Test
    @DisplayName("PROD ohne Vault: Schluesselpaar aus zwei Dateien — die Instanz startet und ihr eigenes Token gilt")
    void prodOhneVaultMitDateien(@TempDir Path dir) throws Exception {
        KeyPair kp = paar();
        Path priv = Files.writeString(dir.resolve("private.pem"), pem("PRIVATE KEY", kp.getPrivate().getEncoded()));
        Path pub = Files.writeString(dir.resolve("public.pem"), pem("PUBLIC KEY", kp.getPublic().getEncoded()));

        VaultwardenSecretService vault = mock(VaultwardenSecretService.class);
        JwtTokenService service = prodDienst(vault);
        ReflectionTestUtils.setField(service, "privateKeyVaultItem", "");
        ReflectionTestUtils.setField(service, "privateKeyFile", priv.toString());
        ReflectionTestUtils.setField(service, "publicKeyFile", pub.toString());

        service.init();

        // Positivkontrolle: nicht "kam hoch", sondern "das eigene Token wird auch angenommen".
        // Ein Test, der nur init() ohne Ausnahme prueft, wuerde auch dann gruen, wenn der
        // oeffentliche Schluessel gar nicht zum privaten gehoert.
        String token = service.generateToken(42L, "guild");
        assertTrue(service.validateToken(token).isPresent(), "das selbst signierte Token muss gelten");
        assertEquals(1, service.getPublicKeysForPublication().size());
        // Der Vault wurde dabei kein einziges Mal gefragt — genau das ist der Zweck.
        verify(vault, never()).getField(any(), any());
    }

    @Test
    @DisplayName("PROD ohne Vault-Item und ohne public-key-file: der Start scheitert weiter (Karte 347)")
    void prodOhneBeidesScheitertWeiterhin(@TempDir Path dir) throws Exception {
        KeyPair kp = paar();
        Path priv = Files.writeString(dir.resolve("private.pem"), pem("PRIVATE KEY", kp.getPrivate().getEncoded()));

        JwtTokenService service = prodDienst(mock(VaultwardenSecretService.class));
        ReflectionTestUtils.setField(service, "privateKeyVaultItem", "");
        ReflectionTestUtils.setField(service, "privateKeyFile", priv.toString());

        IllegalStateException e = assertThrows(IllegalStateException.class, service::init);
        assertTrue(e.getMessage().contains("Cannot initialize JWT service without RSA keys"));
    }

    @Test
    @DisplayName("Fremder oeffentlicher Schluessel in der Datei: Start scheitert, statt spaeter 401 zu liefern")
    void fremderPublicKeyWirdAbgelehnt(@TempDir Path dir) throws Exception {
        KeyPair eigenes = paar();
        KeyPair fremdes = paar();
        Path priv = Files.writeString(dir.resolve("private.pem"), pem("PRIVATE KEY", eigenes.getPrivate().getEncoded()));
        Path pub = Files.writeString(dir.resolve("public.pem"), pem("PUBLIC KEY", fremdes.getPublic().getEncoded()));

        JwtTokenService service = prodDienst(mock(VaultwardenSecretService.class));
        ReflectionTestUtils.setField(service, "privateKeyVaultItem", "");
        ReflectionTestUtils.setField(service, "privateKeyFile", priv.toString());
        ReflectionTestUtils.setField(service, "publicKeyFile", pub.toString());

        IllegalStateException e = assertThrows(IllegalStateException.class, service::init);
        Throwable ursache = e.getCause() != null ? e.getCause() : e;
        assertTrue(ursache.getMessage().contains("passt nicht zum privaten"),
                "die Ursache muss den Schluesselvergleich nennen, nicht nur 'kam nicht hoch': " + ursache.getMessage());
    }

    @Test
    @DisplayName("Ist der Vault gesetzt und liefert, bleibt alles wie bisher — die Datei wird nicht angefasst")
    void vaultBleibtVorrangig(@TempDir Path dir) throws Exception {
        KeyPair ausVault = paar();
        KeyPair inDatei = paar();
        // Die Datei traegt bewusst ein FREMDES Paar: wuerde sie trotz Vault gelesen, schluege
        // der Modul-Abgleich an und der Test fiele um. Er belegt damit, dass sie ungelesen bleibt.
        Path pub = Files.writeString(dir.resolve("public.pem"), pem("PUBLIC KEY", inDatei.getPublic().getEncoded()));

        VaultwardenSecretService vault = mock(VaultwardenSecretService.class);
        when(vault.isEnabled()).thenReturn(true);
        when(vault.getField(any(), any())).thenAnswer(a -> "private_key_pem".equals(a.getArgument(1))
                ? Optional.of(pem("PRIVATE KEY", ausVault.getPrivate().getEncoded()))
                : Optional.of(pem("PUBLIC KEY", ausVault.getPublic().getEncoded())));

        JwtTokenService service = prodDienst(vault);
        ReflectionTestUtils.setField(service, "privateKeyVaultItem", "schuetu.jwt-signing-key");
        ReflectionTestUtils.setField(service, "publicKeyFile", pub.toString());

        service.init();

        String token = service.generateToken(42L, "schuetu");
        assertTrue(service.validateToken(token).isPresent(), "der Vault-Schluessel muss weiterhin gelten");
    }

    @Test
    @DisplayName("Vault gesetzt, aber stumm: die Datei springt ein — sofern sie zum privaten Schluessel passt")
    void dateiSpringtEinWennDerVaultSchweigt(@TempDir Path dir) throws Exception {
        KeyPair kp = paar();
        Path priv = Files.writeString(dir.resolve("private.pem"), pem("PRIVATE KEY", kp.getPrivate().getEncoded()));
        Path pub = Files.writeString(dir.resolve("public.pem"), pem("PUBLIC KEY", kp.getPublic().getEncoded()));

        VaultwardenSecretService vault = mock(VaultwardenSecretService.class);
        when(vault.isEnabled()).thenReturn(false);

        JwtTokenService service = prodDienst(vault);
        ReflectionTestUtils.setField(service, "privateKeyVaultItem", "schuetu.jwt-signing-key");
        ReflectionTestUtils.setField(service, "privateKeyFile", priv.toString());
        ReflectionTestUtils.setField(service, "publicKeyFile", pub.toString());

        service.init();

        String token = service.generateToken(42L, "schuetu");
        assertTrue(service.validateToken(token).isPresent());
    }
}
