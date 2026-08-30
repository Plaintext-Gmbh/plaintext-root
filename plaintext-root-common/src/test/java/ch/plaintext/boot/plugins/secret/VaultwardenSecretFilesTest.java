/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Karte 942: Bootstrap-Geheimnisse aus Dateien statt aus der Container-Umgebung.
 *
 * <p>Der Anlass war eine Messung, kein Verdacht: {@code PLAINTEXT_VAULT_MASTER_PASSWORD} stand am
 * 30.08.2026 im Klartext in der Umgebung aller vier PROD-Container und war damit fuer jeden
 * lesbar, der einen Befehl darin ausfuehren darf.</p>
 */
class VaultwardenSecretFilesTest {

    private static final String ZIEL = "plaintext.vault.master-password";
    private static final String DATEI = "plaintext.vault.master-password-file";

    private static Path schreibe(Path dir, String name, String inhalt) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, inhalt);
        return p;
    }

    @Test
    @DisplayName("Der Wert kommt aus der Datei und steht danach als Property bereit")
    void liestWertAusDatei(@TempDir Path tmp) throws IOException {
        Path f = schreibe(tmp, "pw", "geheim-aus-datei");
        MockEnvironment env = new MockEnvironment().withProperty(DATEI, f.toString());

        VaultwardenSecretFiles.anwenden(env);

        assertThat(env.getProperty(ZIEL)).isEqualTo("geheim-aus-datei");
    }

    @Test
    @DisplayName("Der abschliessende Zeilenumbruch aus 'echo > datei' gehoert nicht zum Passwort")
    void entferntZeilenumbruch(@TempDir Path tmp) throws IOException {
        // Genau so entsteht die Datei in der Praxis — ohne strip() waere das \n Teil des
        // Passworts, und der Vault-Login scheiterte mit einer Meldung, die auf alles andere
        // hindeutet als auf ein Zeichen zu viel.
        Path f = schreibe(tmp, "pw", "geheim\n");
        MockEnvironment env = new MockEnvironment().withProperty(DATEI, f.toString());

        VaultwardenSecretFiles.anwenden(env);

        assertThat(env.getProperty(ZIEL)).isEqualTo("geheim");
    }

    @Test
    @DisplayName("Ist beides gesetzt, gewinnt die direkte Variable")
    void direkteVariableGewinnt(@TempDir Path tmp) throws IOException {
        Path f = schreibe(tmp, "pw", "aus-datei");
        MockEnvironment env = new MockEnvironment()
                .withProperty(ZIEL, "aus-variable")
                .withProperty(DATEI, f.toString());

        VaultwardenSecretFiles.anwenden(env);

        assertThat(env.getProperty(ZIEL)).isEqualTo("aus-variable");
    }

    @Test
    @DisplayName("Eine fehlende Datei bricht den Start ab, statt ohne Geheimnis weiterzulaufen")
    void fehlendeDateiBrichtAb(@TempDir Path tmp) {
        MockEnvironment env = new MockEnvironment()
                .withProperty(DATEI, tmp.resolve("gibt-es-nicht").toString());

        assertThatThrownBy(() -> VaultwardenSecretFiles.anwenden(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DATEI)
                .hasMessageContaining("nicht lesbar");
    }

    @Test
    @DisplayName("Eine leere Datei bricht ebenfalls ab — ein leeres Passwort ist kein Passwort")
    void leereDateiBrichtAb(@TempDir Path tmp) throws IOException {
        Path f = schreibe(tmp, "leer", "   \n");
        MockEnvironment env = new MockEnvironment().withProperty(DATEI, f.toString());

        assertThatThrownBy(() -> VaultwardenSecretFiles.anwenden(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("leer");
    }

    @Test
    @DisplayName("Ohne *-file-Property passiert gar nichts")
    void ohneAngabePassiertNichts() {
        MockEnvironment env = new MockEnvironment();

        VaultwardenSecretFiles.anwenden(env);

        assertThat(env.getPropertySources().contains(VaultwardenSecretFiles.SOURCE_NAME)).isFalse();
        assertThat(env.getProperty(ZIEL)).isNull();
    }

    @Test
    @DisplayName("Auch clientId und clientSecret lassen sich aus Dateien lesen")
    void auchClientCredentials(@TempDir Path tmp) throws IOException {
        MockEnvironment env = new MockEnvironment()
                .withProperty("plaintext.vault.client-id-file", schreibe(tmp, "cid", "user.abc").toString())
                .withProperty("plaintext.vault.client-secret-file", schreibe(tmp, "cs", "s3cr3t").toString());

        VaultwardenSecretFiles.anwenden(env);

        assertThat(env.getProperty("plaintext.vault.client-id")).isEqualTo("user.abc");
        assertThat(env.getProperty("plaintext.vault.client-secret")).isEqualTo("s3cr3t");
    }
}
