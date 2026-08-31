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
 * LIVE verification against our own Vaultwarden instance — runs ONLY locally.
 *
 * <p>Guarded against CI twice over: {@code @Tag("live-vault")} AND
 * {@code @EnabledIfEnvironmentVariable(VAULT_LIVE=true)}. In CI the environment
 * variable is not set, so the test is skipped automatically (CI has no vault
 * credentials).</p>
 *
 * <p>Local run (credentials exclusively from the env, never in the repo):</p>
 * <pre>
 * export VAULT_LIVE=true
 * export PLAINTEXT_VAULT_EMAIL=&lt;service-account-email&gt;
 * export PLAINTEXT_VAULT_MASTER_PASSWORD=...            # from the password vault, never in the repo
 * export PLAINTEXT_VAULT_URL=https://vault.example.org
 * export VAULT_ITEM_NAME='&lt;name of an item in your own vault&gt;'
 * export VAULT_EXPECTED_PASSWORD="$(bw get password "$VAULT_ITEM_NAME")"
 * mvn -pl plaintext-root-common test \
 *     -Dtest=VaultwardenLiveVerificationTest -Dmaven.build.cache.enabled=false
 * </pre>
 *
 * <p>The test decrypts the named item natively and compares the SHA-256 of the
 * result with the SHA-256 of the {@code bw} output. NO plaintext secrets are
 * printed, only the hash and JA/NEIN.</p>
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
