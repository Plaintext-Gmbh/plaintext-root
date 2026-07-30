/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * LIVE-Verifikation gegen die eigene Vaultwarden-Instanz — laeuft NUR lokal.
 *
 * <p>Doppelt gegen CI abgesichert: {@code @Tag("live-vault")} UND
 * {@code @EnabledIfEnvironmentVariable(VAULT_LIVE=true)}. In der CI ist die
 * Umgebungsvariable nicht gesetzt, daher wird der Test automatisch uebersprungen
 * (die CI hat keine Vault-Credentials).</p>
 *
 * <p>Lokaler Lauf (Creds ausschliesslich aus Env, nie im Repo):</p>
 * <pre>
 * export VAULT_LIVE=true
 * export PLAINTEXT_VAULT_EMAIL=&lt;service-account-email&gt;
 * export PLAINTEXT_VAULT_MASTER_PASSWORD=...            # aus dem Passwort-Tresor, nie im Repo
 * export PLAINTEXT_VAULT_URL=https://vault.example.org
 * export VAULT_ITEM_NAME='&lt;Name eines Items im eigenen Tresor&gt;'
 * export VAULT_EXPECTED_PASSWORD="$(bw get password "$VAULT_ITEM_NAME")"
 * mvn -pl plaintext-root-common test \
 *     -Dtest=VaultwardenLiveVerificationTest -Dmaven.build.cache.enabled=false
 * </pre>
 *
 * <p>Der Test entschluesselt das genannte Item nativ und vergleicht den
 * SHA-256 des Ergebnisses mit dem SHA-256 der {@code bw}-Ausgabe. Es werden
 * KEINE Klartext-Secrets ausgegeben, nur der Hash und JA/NEIN.</p>
 */
@Tag("live-vault")
@EnabledIfEnvironmentVariable(named = "VAULT_LIVE", matches = "true")
class VaultwardenLiveVerificationTest {

    @Test
    void decryptedPaperlessPasswordMatchesBwCli() throws Exception {
        String email = env("PLAINTEXT_VAULT_EMAIL");
        String masterPw = env("PLAINTEXT_VAULT_MASTER_PASSWORD");
        String expected = env("VAULT_EXPECTED_PASSWORD");
        String itemName = env("VAULT_ITEM_NAME");

        VaultwardenProperties props = new VaultwardenProperties();
        props.setEnabled(true);
        props.setUrl(env("PLAINTEXT_VAULT_URL"));
        props.setEmail(email);
        props.setMasterPassword(masterPw);

        VaultwardenSecretService service =
                new VaultwardenSecretService(props, new VaultwardenClient(props, "live-verify-test"));

        Optional<String> decrypted = service.getPassword(itemName);

        boolean present = decrypted.isPresent();
        String decHash = present ? sha256Hex(decrypted.get()) : "(none)";
        String expHash = sha256Hex(expected);
        boolean match = present && decHash.equals(expHash);

        System.out.println("=== LIVE-VAULT VERIFICATION ===");
        System.out.println("item              : " + itemName);
        System.out.println("decrypted present : " + present);
        System.out.println("sha256(decrypted) : " + decHash);
        System.out.println("sha256(bw output) : " + expHash);
        System.out.println("LIVE-VAULT MATCH  : " + (match ? "JA" : "NEIN"));

        assertThat(present).as("Item '%s' entschluesselt", itemName).isTrue();
        assertThat(decHash).as("entschluesseltes Passwort == bw-Ausgabe").isEqualTo(expHash);
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Env-Variable " + name + " fehlt (nur fuer lokalen Live-Test)");
        }
        return v;
    }

    private static String sha256Hex(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(d);
    }
}
