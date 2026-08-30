/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the bootstrap secrets of the vault client from a <b>file</b> instead of from an
 * environment variable — the {@code *_FILE} convention as known from Postgres, MySQL and most
 * Docker images (Karte 942).
 *
 * <h2>Why this became necessary</h2>
 * <p>Measured on 30.08.2026: {@code PLAINTEXT_VAULT_MASTER_PASSWORD} sat in plaintext in the
 * environment of <b>all four</b> PROD containers. An environment variable is readable by
 * anyone allowed to run a command in the container ({@code printenv}, one command, no sudo) —
 * and it additionally ends up in {@code docker inspect}, in crash dumps, in process listings
 * and in every support dump that records the environment.</p>
 *
 * <p><b>Why not simply an API key.</b> The obvious idea — a personal API key
 * ({@code clientId}/{@code clientSecret}) instead of the master password — does not hold, and
 * that has been measured: {@link VaultwardenClient} needs the master password not only to log
 * in but to <b>decrypt</b> the user key ({@code stretchMasterKey(masterKey)}). The API key
 * only replaces the token grant. Bitwarden encrypts client-side; without the key derived from
 * the master password the entries stay unreadable.</p>
 *
 * <h2>What this class achieves — and what it does not</h2>
 * <p>It takes the value out of the environment. In the container's file system it is still
 * there; whoever may read there can get at it. The difference is the number of ways in: a file
 * with {@code 0400} is read by the application user, a {@code printenv} is read by every
 * process, and {@code docker inspect} is read by everyone with Docker access — <b>without</b>
 * having to get into the container at all.</p>
 *
 * <h2>Usage (app.env)</h2>
 * <pre>
 * # instead of:
 * # PLAINTEXT_VAULT_MASTER_PASSWORD=...
 * PLAINTEXT_VAULT_MASTER_PASSWORD_FILE=/run/secrets/vault-master-password
 * </pre>
 * <p>Additionally mount the file read-only in the compose file. If <b>both</b> are set, the
 * direct variable wins and a WARN is logged — otherwise one keeps fixing the file and wonders
 * why nothing happens.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
final class VaultwardenSecretFiles {

    /** Property name of the source that carries the values read from files. */
    static final String SOURCE_NAME = "vaultwardenSecretFiles";

    /**
     * Property pairs: the target key and the key that points at the file.
     * Deliberately a fixed, small list instead of a generic {@code *_FILE} rule across all
     * properties: these three are the bootstrap secrets of the vault client; everything else
     * comes from the vault afterwards as a {@code vault:} reference and is not in the
     * environment anyway.
     */
    private static final Map<String, String> ZIEL_ZU_DATEI = Map.of(
            "plaintext.vault.master-password", "plaintext.vault.master-password-file",
            "plaintext.vault.client-secret", "plaintext.vault.client-secret-file",
            "plaintext.vault.client-id", "plaintext.vault.client-id-file");

    private VaultwardenSecretFiles() {
    }

    /**
     * Attaches — if {@code *-file} properties are set and the files are readable — a property
     * source with the values that were read into the environment.
     *
     * <p><b>Fail fast on a real error:</b> if a {@code *-file} property points at a file that is
     * missing or unreadable, startup aborts. The alternative would be to carry on silently
     * without the secret — and that ends in a vault login error whose cause nobody traces back
     * to the file setting.</p>
     */
    static void anwenden(ConfigurableEnvironment environment) {
        if (environment.getPropertySources().contains(SOURCE_NAME)) {
            return; // idempotent
        }
        Map<String, Object> gelesen = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : ZIEL_ZU_DATEI.entrySet()) {
            String ziel = e.getKey();
            String pfad = environment.getProperty(e.getValue());
            if (pfad == null || pfad.isBlank()) {
                continue;
            }
            if (environment.getProperty(ziel) != null) {
                log.warn("{} UND {} sind gesetzt — die direkte Variable gewinnt, die Datei wird "
                        + "ignoriert. Bitte eine der beiden entfernen.", ziel, e.getValue());
                continue;
            }
            gelesen.put(ziel, lies(pfad.trim(), e.getValue()));
            // Deliberately only the path and the length in the log — never the value (Karte 667).
            log.info("Vault-Bootstrap: {} aus Datei gelesen ({})", ziel, pfad.trim());
        }
        if (!gelesen.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, gelesen));
        }
    }

    /**
     * Reads a secret file. A trailing line break is removed — it arises with every
     * {@code echo > file} and would otherwise be part of the password, which ends in a login
     * error that nobody would attribute to the file.
     */
    private static String lies(String pfad, String propertyName) {
        Path p = Path.of(pfad);
        try {
            String inhalt = Files.readString(p, StandardCharsets.UTF_8);
            String wert = inhalt.strip();
            if (wert.isEmpty()) {
                throw new IllegalStateException(propertyName + " zeigt auf " + pfad
                        + ", aber die Datei ist leer.");
            }
            return wert;
        } catch (IOException ex) {
            throw new IllegalStateException(propertyName + " zeigt auf " + pfad
                    + ", aber die Datei ist nicht lesbar: " + ex.getMessage()
                    + ". Der Start wird abgebrochen, damit die Anwendung nicht ohne ihr "
                    + "Bootstrap-Geheimnis weiterlaeuft.", ex);
        }
    }
}
