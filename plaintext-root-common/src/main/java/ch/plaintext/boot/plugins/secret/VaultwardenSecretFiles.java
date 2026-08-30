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
 * Liest die Bootstrap-Geheimnisse des Vault-Clients aus einer <b>Datei</b> statt aus einer
 * Umgebungsvariablen — die {@code *_FILE}-Konvention, wie sie Postgres, MySQL und die meisten
 * Docker-Images kennen (Karte 942).
 *
 * <h2>Warum das noetig wurde</h2>
 * <p>Am 30.08.2026 gemessen: {@code PLAINTEXT_VAULT_MASTER_PASSWORD} stand im Klartext in der
 * Umgebung <b>aller vier</b> PROD-Container. Eine Umgebungsvariable ist fuer jeden lesbar, der
 * einen Befehl im Container ausfuehren darf ({@code printenv}, ein Befehl, kein sudo) — und sie
 * landet zusaetzlich in {@code docker inspect}, in Crash-Dumps, in Prozesslisten und in jeder
 * Support-Ausgabe, die die Umgebung mitschreibt.</p>
 *
 * <p><b>Warum nicht einfach ein API-Key.</b> Der naheliegende Gedanke — Personal-API-Key
 * ({@code clientId}/{@code clientSecret}) statt Master-Passwort — traegt nicht, und das ist
 * gemessen: {@link VaultwardenClient} braucht das Master-Passwort nicht nur zum Anmelden, sondern
 * zum <b>Entschluesseln</b> des UserKeys ({@code stretchMasterKey(masterKey)}). Der API-Key
 * ersetzt nur den Token-Grant. Bitwarden verschluesselt clientseitig; ohne den aus dem
 * Master-Passwort abgeleiteten Schluessel bleiben die Eintraege unlesbar.</p>
 *
 * <h2>Was diese Klasse leistet — und was nicht</h2>
 * <p>Sie holt den Wert aus der Umgebung heraus. Im Dateisystem des Containers steht er weiterhin;
 * wer dort lesen darf, kommt heran. Der Unterschied ist die Zahl der Wege: eine Datei mit
 * {@code 0400} liest der Anwendungsbenutzer, ein {@code printenv} liest jeder Prozess, und
 * {@code docker inspect} liest jeder mit Docker-Zugriff — <b>ohne</b> in den Container zu
 * muessen.</p>
 *
 * <h2>Verwendung (app.env)</h2>
 * <pre>
 * # statt:
 * # PLAINTEXT_VAULT_MASTER_PASSWORD=...
 * PLAINTEXT_VAULT_MASTER_PASSWORD_FILE=/run/secrets/vault-master-password
 * </pre>
 * <p>Dazu im Compose die Datei read-only einhaengen. Ist <b>beides</b> gesetzt, gewinnt die
 * direkte Variable und es wird eine WARN geloggt — sonst repariert man an der Datei und wundert
 * sich, warum nichts passiert.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
final class VaultwardenSecretFiles {

    /** Property-Name der Quelle, die die aus Dateien gelesenen Werte traegt. */
    static final String SOURCE_NAME = "vaultwardenSecretFiles";

    /**
     * Property-Paare: der Zielschluessel und der Schluessel, der auf die Datei zeigt.
     * Bewusst eine feste, kleine Liste statt einer generischen {@code *_FILE}-Regel ueber alle
     * Properties: Diese drei sind die Bootstrap-Geheimnisse des Vault-Clients: alles andere kommt
     * anschliessend als {@code vault:}-Referenz aus dem Tresor und steht ohnehin nicht in der
     * Umgebung.
     */
    private static final Map<String, String> ZIEL_ZU_DATEI = Map.of(
            "plaintext.vault.master-password", "plaintext.vault.master-password-file",
            "plaintext.vault.client-secret", "plaintext.vault.client-secret-file",
            "plaintext.vault.client-id", "plaintext.vault.client-id-file");

    private VaultwardenSecretFiles() {
    }

    /**
     * Haengt — falls {@code *-file}-Properties gesetzt sind und die Dateien lesbar sind — eine
     * Property-Quelle mit den gelesenen Werten ins Environment.
     *
     * <p><b>Fail-fast bei einem echten Fehler:</b> Zeigt ein {@code *-file}-Property auf eine
     * Datei, die fehlt oder nicht lesbar ist, bricht der Start ab. Die Alternative waere, still
     * ohne Geheimnis weiterzulaufen — und das endet in einem Vault-Login-Fehler, dessen Ursache
     * niemand mehr der Dateiangabe zuordnet.</p>
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
            // Bewusst nur Pfad und Laenge im Log — nie der Wert (Karte 667).
            log.info("Vault-Bootstrap: {} aus Datei gelesen ({})", ziel, pfad.trim());
        }
        if (!gelesen.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, gelesen));
        }
    }

    /**
     * Liest eine Geheimnis-Datei. Ein abschliessender Zeilenumbruch wird entfernt — er entsteht
     * bei jedem {@code echo > datei} und waere sonst Teil des Passworts, was in einem
     * Anmeldefehler endet, den niemand der Datei ansieht.
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
