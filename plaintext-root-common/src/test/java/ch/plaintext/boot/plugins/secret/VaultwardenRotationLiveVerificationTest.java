/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * LIVE-Verifikation der ROTATION (Schreibrichtung) gegen die eigene Vaultwarden-Instanz — laeuft NUR lokal.
 *
 * <p>Doppelt gegen CI abgesichert: {@code @Tag("live-vault")} UND
 * {@code @EnabledIfEnvironmentVariable(VAULT_LIVE=true)}. In der CI ist die
 * Umgebungsvariable nicht gesetzt, daher wird der Test automatisch uebersprungen.</p>
 *
 * <p><b>Sicherheit:</b> Dieser Test legt NICHTS im Tresor an und rotiert KEIN
 * Produktiv-Item. Er schreibt ausschliesslich dann, wenn ein dediziertes Item namens
 * {@code VaultRotationSelfTest} existiert. Fehlt es, wird der Schreibpfad NICHT live
 * geprueft (Test besteht mit Hinweis) — der Read-Flow ist ohnehin separat verifiziert.</p>
 *
 * <p>Lokaler Lauf (Creds ausschliesslich aus Env, nie im Repo):</p>
 * <pre>
 * export VAULT_LIVE=true
 * export PLAINTEXT_VAULT_EMAIL=&lt;service-account-email&gt;
 * export PLAINTEXT_VAULT_MASTER_PASSWORD=...
 * mvn -pl plaintext-root-common test \
 *     -Dtest=VaultwardenRotationLiveVerificationTest -Dmaven.build.cache.enabled=false
 * </pre>
 *
 * <p>Der Test setzt das Passwort des Self-Test-Items auf einen Zufallswert und liest
 * es via {@link VaultwardenSecretService#getPassword(String)} zurueck (== gesetzt).
 * Es werden KEINE Klartext-Secrets ausgegeben, nur JA/NEIN.</p>
 */
@Tag("live-vault")
@EnabledIfEnvironmentVariable(named = "VAULT_LIVE", matches = "true")
class VaultwardenRotationLiveVerificationTest {

    private static final String SELF_TEST_ITEM = "VaultRotationSelfTest";

    @Test
    void rotatesSelfTestItemAndReadsBack() {
        String email = env("PLAINTEXT_VAULT_EMAIL");
        String masterPw = env("PLAINTEXT_VAULT_MASTER_PASSWORD");

        VaultwardenProperties props = new VaultwardenProperties();
        props.setEnabled(true);
        props.setUrl(env("PLAINTEXT_VAULT_URL"));
        props.setEmail(email);
        props.setMasterPassword(masterPw);

        VaultwardenSecretService service =
                new VaultwardenSecretService(props, new VaultwardenClient(props, "live-verify-test"));

        // NUR schreiben, wenn das dedizierte Self-Test-Item bereits existiert.
        Optional<VaultwardenItem> selfTest = service.getSecret(SELF_TEST_ITEM);
        if (selfTest.isEmpty()) {
            System.out.println("=== LIVE-VAULT ROTATION ===");
            System.out.println("Self-Test-Item '" + SELF_TEST_ITEM
                    + "' nicht vorhanden -> Rotation-Write NICHT live geprueft (nichts angelegt).");
            return; // bewusst kein Fehler: nichts im echten Tresor anlegen
        }

        String newPassword = randomToken();
        boolean rotated = service.rotatePassword(SELF_TEST_ITEM, newPassword);
        Optional<String> readBack = service.getPassword(SELF_TEST_ITEM);
        boolean match = readBack.map(newPassword::equals).orElse(false);

        System.out.println("=== LIVE-VAULT ROTATION ===");
        System.out.println("item             : " + SELF_TEST_ITEM);
        System.out.println("rotate returned  : " + rotated);
        System.out.println("read-back present: " + readBack.isPresent());
        System.out.println("ROTATION MATCH   : " + (match ? "JA" : "NEIN"));

        assertThat(rotated).as("rotatePassword lieferte true").isTrue();
        assertThat(match).as("zurueckgelesenes Passwort == gesetzter Zufallswert").isTrue();
    }

    private static String randomToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return "rot-" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Env-Variable " + name + " fehlt (nur fuer lokalen Live-Test)");
        }
        return v;
    }
}
