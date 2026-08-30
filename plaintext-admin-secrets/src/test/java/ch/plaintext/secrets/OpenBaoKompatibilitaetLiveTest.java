/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;

/**
 * Karte 855, Schritt 1: <b>belegt</b>, dass OpenBao die Vault-KV-v2-API so spricht, wie
 * {@link HashiCorpVaultBackend} sie benutzt — statt es anzunehmen.
 *
 * <p><b>Warum gegen die echte Klasse und nicht per {@code curl}.</b> Ein {@code curl} zeigt nur,
 * dass OpenBao HTTP beantwortet. Die Frage dieser Karte ist eine andere: Kommt <i>unser</i> Client
 * damit zurecht — mit seinen Pfaden ({@code /v1/<mount>/data/<name>}), seinem Header
 * ({@code X-Vault-Token}) und seiner Erwartung an die Antwortform? Deshalb laeuft hier der
 * unveraenderte Produktionscode; gemockt ist ausschliesslich die Herkunft der Konfiguration
 * (sie kaeme sonst verschluesselt aus der Datenbank).</p>
 *
 * <p><b>Was dieser Test NICHT beantwortet:</b> die Betriebsfragen der Karte. Die Instanz laeuft im
 * Dev-Modus und ist damit dauerhaft entsiegelt — die Unseal-Frage (Schritt 2) ist hier
 * ausgeklammert, nicht geloest. Wer aus diesem gruenen Test schliesst, der Betrieb sei geklaert,
 * macht genau den Fehler, vor dem die Karte warnt.</p>
 *
 * <p>Doppelt gegen die CI abgesichert ({@code @Tag} + Umgebungsvariable), weil er eine laufende
 * Instanz braucht. Lokaler Lauf:</p>
 * <pre>
 * docker run -d --rm --name openbao855 -p 8200:8200 \
 *   -e BAO_DEV_ROOT_TOKEN_ID=test-root-855 -e BAO_DEV_LISTEN_ADDRESS=0.0.0.0:8200 \
 *   openbao/openbao:latest server -dev
 *
 * export OPENBAO_LIVE=true
 * export OPENBAO_URL=http://127.0.0.1:8200
 * export OPENBAO_TOKEN=test-root-855
 * mvn -pl plaintext-admin-secrets test \
 *     -Dtest=OpenBaoKompatibilitaetLiveTest -Dmaven.build.cache.enabled=false
 * </pre>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Tag("live-openbao")
@EnabledIfEnvironmentVariable(named = "OPENBAO_LIVE", matches = "true")
class OpenBaoKompatibilitaetLiveTest {

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    /**
     * Baut das echte Backend, aber mit einer Konfiguration aus der Umgebung statt aus der
     * verschluesselten Datenbankzeile. {@code config()} ist privat und liest ueber Repository und
     * {@link SecretCrypto} — beide werden hier so gemockt, dass sie unser Test-JSON liefern.
     */
    private static HashiCorpVaultBackend backendMitTestConfig() {
        String json = "{\"url\":\"" + env("OPENBAO_URL", "http://127.0.0.1:8200")
                + "\",\"token\":\"" + env("OPENBAO_TOKEN", "test-root-855")
                + "\",\"mount\":\"" + env("OPENBAO_MOUNT", "secret") + "\"}";

        SecretBackendConfig cfg = new SecretBackendConfig();
        cfg.setBackendType(SecretBackendType.HASHICORP);
        cfg.setConfigEncrypted("egal-wird-gemockt");

        SecretBackendConfigRepository repo = Mockito.mock(SecretBackendConfigRepository.class);
        Mockito.when(repo.findFirstByMandatAndAktivAndDeleted(any(), anyBoolean(), anyBoolean()))
                .thenReturn(Optional.of(cfg));

        SecretCrypto crypto = Mockito.mock(SecretCrypto.class);
        Mockito.when(crypto.decrypt(anyString())).thenReturn(json);

        return new HashiCorpVaultBackend(repo, crypto);
    }

    @Test
    @DisplayName("OpenBao beantwortet Health, Schreiben und Lesen so, wie der Vault-Client es erwartet")
    void schreibenUndLesenMitGegenprobe() {
        // config() liest den Mandanten aus dem statischen Holder; im Test ist kein Kontext
        // aufgespannt, deshalb hier einer vorgegeben.
        try (MockedStatic<PlaintextSecurityHolder> sec = mockStatic(PlaintextSecurityHolder.class)) {
        sec.when(PlaintextSecurityHolder::getMandat).thenReturn("test");
        HashiCorpVaultBackend backend = backendMitTestConfig();

        // --- Health: der Client fragt /v1/auth/token/lookup-self ---
        assertThat(backend.isAvailable())
                .as("Config muss gelesen werden, sonst prueft der Test nichts")
                .isTrue();
        SecretHealth health = backend.health();
        assertThat(health.ok())
                .as("health() sagt: %s", health.detail())
                .isTrue();

        // --- Schreibpfad ---
        String name = "karte855-probe-" + UUID.randomUUID().toString().substring(0, 8);
        String wert = "wert-" + UUID.randomUUID();
        String notiz = "Kompatibilitaetsprobe Karte 855";
        backend.set(name, wert, notiz);

        // --- Lesepfad, Gegenprobe zum Schreiben ---
        assertThat(backend.readValue(name))
                .as("zurueckgelesener Wert muss dem geschriebenen entsprechen")
                .isEqualTo(wert);
        assertThat(backend.comment(name))
                .as("die Notiz wird in demselben Eintrag neben dem Wert gefuehrt")
                .isEqualTo(notiz);

        // --- NEGATIVKONTROLLE: ohne sie waere der Test auch gruen, wenn readValue immer denselben
        //     Wert lieferte oder Fehler still verschluckte. Ein unbekannter Schluessel MUSS als
        //     "nicht gefunden" ankommen, nicht als Erfolg mit leerem Wert.
        assertThat(backend.readValue("gibt-es-nicht-" + UUID.randomUUID()))
                .as("unbekannter Schluessel darf keinen Wert liefern")
                .isNull();
        }
    }

    @Test
    @DisplayName("Ein zweites set() ueberschreibt — KV v2 legt eine neue Version an, der Client liest die neueste")
    void ueberschreibenLiefertDenNeuenWert() {
        try (MockedStatic<PlaintextSecurityHolder> sec = mockStatic(PlaintextSecurityHolder.class)) {
        sec.when(PlaintextSecurityHolder::getMandat).thenReturn("test");
        HashiCorpVaultBackend backend = backendMitTestConfig();
        String name = "karte855-version-" + UUID.randomUUID().toString().substring(0, 8);

        backend.set(name, "erst", "v1");
        backend.set(name, "dann", "v2");

        // KV v2 versioniert: der Client muss die aktuelle Version bekommen, nicht die erste.
        assertThat(backend.readValue(name)).isEqualTo("dann");
        assertThat(backend.comment(name)).isEqualTo("v2");
        }
    }
}
