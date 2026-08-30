/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Karte 995: die {@code bao:}-Referenz — gegen einen echten kleinen HTTP-Server, nicht gegen einen
 * gemockten Client.
 *
 * <p><b>Warum ein echter Server:</b> Ein Mock haette nur belegt, dass der Resolver den Client
 * aufruft. Die Fragen, an denen so etwas scheitert, liegen aber tiefer: Trifft der Pfad
 * {@code /v1/<mount>/data/<schluessel>}? Steht der Token im richtigen Header? Wird die
 * KV-v2-Verschachtelung {@code data.data.<feld>} richtig ausgepackt? Wird ein 404 als
 * <em>definitive</em> Antwort behandelt und ein 503 als transiente? Das prueft nur echtes HTTP.</p>
 */
@DisplayName("bao:-Referenzen gegen OpenBao")
class OpenBaoReferenzTest {

    private HttpServer server;
    private String basisUrl;
    private final List<String> pfade = new ArrayList<>();
    private final List<String> tokenHeader = new ArrayList<>();
    private volatile int status = 200;
    private volatile String rumpf = "{\"data\":{\"data\":{\"value\":\"geheim\"}}}";
    private final AtomicInteger aufrufe = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            aufrufe.incrementAndGet();
            pfade.add(ex.getRequestURI().getPath());
            tokenHeader.add(String.valueOf(ex.getRequestHeaders().getFirst("X-Vault-Token")));
            byte[] b = rumpf.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
        basisUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OpenBaoClient client() {
        return new OpenBaoClient(basisUrl, "test-token", "secret", 5);
    }

    /** Resolver ohne Wartezeit — sonst dauert der Retry-Test 35 Sekunden. */
    private VaultwardenValueResolver resolver() {
        return new VaultwardenValueResolver(() -> null, this::client, ms -> { });
    }

    @Test
    @DisplayName("bao:<pfad> liest das Feld value und trifft /v1/<mount>/data/<pfad>")
    void liestDefaultFeld() {
        String wert = resolver().resolve("app.smtp-passwort", "bao:app-smtp");

        assertEquals("geheim", wert);
        assertEquals("/v1/secret/data/app-smtp", pfade.get(0));
        assertEquals("test-token", tokenHeader.get(0));
    }

    @Test
    @DisplayName("bao:<pfad>#<feld> liest das genannte Feld")
    void liestGenanntesFeld() {
        rumpf = "{\"data\":{\"data\":{\"value\":\"falsch\",\"benutzer\":\"richtig\"}}}";

        assertEquals("richtig", resolver().resolve("app.user", "bao:app-smtp#benutzer"));
    }

    @Test
    @DisplayName("vault:-Werte laufen unveraendert weiter — bao: ist additiv")
    void vaultBleibtUnberuehrt() {
        // Der Vaultwarden-Zweig wird gar nicht erst betreten: isBaoReference trennt sauber.
        assertTrue(VaultwardenValueResolver.isBaoReference("bao:app-smtp"));
        assertFalse(VaultwardenValueResolver.isBaoReference("vault:app.smtp"));
        // Beide gelten aber als Referenz, damit die PropertySource beide abfaengt.
        assertTrue(VaultwardenValueResolver.isVaultReference("vault:app.smtp"));
        assertTrue(VaultwardenValueResolver.isVaultReference("bao:app-smtp"));
        assertFalse(VaultwardenValueResolver.isVaultReference("ein-normaler-wert"));
    }

    @Test
    @DisplayName("404 ist definitiv: Fail-fast OHNE Retry")
    void fehlenderEintragScheitertSofort() {
        status = 404;
        rumpf = "{\"errors\":[]}";

        VaultwardenPropertyResolutionException e = assertThrows(
                VaultwardenPropertyResolutionException.class,
                () -> resolver().resolve("app.fehlt", "bao:gibtesnicht"));

        assertTrue(e.getMessage().contains("existiert nicht"), e.getMessage());
        // Der entscheidende Teil: KEIN Retry. Ein Tippfehler darf den Start nicht 35 s aufhalten.
        assertEquals(1, aufrufe.get());
    }

    @Test
    @DisplayName("503 (versiegelt) ist transient: es wird wiederholt, dann Fail-fast")
    void versiegelterTresorWirdWiederholt() {
        status = 503;
        rumpf = "{\"errors\":[\"Vault is sealed\"]}";

        assertThrows(VaultwardenPropertyResolutionException.class,
                () -> resolver().resolve("app.x", "bao:app-x"));

        // BOOT_MAX_VERSUCHE = 4 -> ein erster Versuch plus drei Wiederholungen.
        assertEquals(VaultwardenValueResolver.BOOT_MAX_VERSUCHE, aufrufe.get());
    }

    @Test
    @DisplayName("403 ist definitiv: Policy-Fehler wird nicht wiederholt")
    void zugriffVerweigertScheitertSofort() {
        status = 403;
        rumpf = "{\"errors\":[\"permission denied\"]}";

        VaultwardenPropertyResolutionException e = assertThrows(
                VaultwardenPropertyResolutionException.class,
                () -> resolver().resolve("app.x", "bao:app-x"));

        assertTrue(e.getMessage().contains("verweigert"), e.getMessage());
        assertEquals(1, aufrufe.get());
    }

    @Test
    @DisplayName("fehlendes Feld im vorhandenen Eintrag scheitert mit Namen des Feldes")
    void fehlendesFeld() {
        rumpf = "{\"data\":{\"data\":{\"anderes\":\"x\"}}}";

        VaultwardenPropertyResolutionException e = assertThrows(
                VaultwardenPropertyResolutionException.class,
                () -> resolver().resolve("app.x", "bao:app-x#value"));

        assertTrue(e.getMessage().contains("value"), e.getMessage());
    }

    @Test
    @DisplayName("ohne konfigurierten Client sagt die Meldung, was fehlt")
    void ohneClient() {
        VaultwardenValueResolver r =
                new VaultwardenValueResolver(() -> null, () -> null, ms -> { });

        VaultwardenPropertyResolutionException e = assertThrows(
                VaultwardenPropertyResolutionException.class,
                () -> r.resolve("app.x", "bao:app-x"));

        assertTrue(e.getMessage().contains("nicht konfiguriert"), e.getMessage());
    }

    @Test
    @DisplayName("derselbe Wert wird nur einmal geholt (Boot-Cache)")
    void cacheGreift() {
        VaultwardenValueResolver r = resolver();
        assertEquals("geheim", r.resolve("app.a", "bao:app-smtp"));
        assertEquals("geheim", r.resolve("app.b", "bao:app-smtp"));

        assertEquals(1, aufrufe.get());
    }

    @Test
    @DisplayName("leerer Pfad ist ein Fehler, kein Zugriff")
    void leererPfad() {
        assertThrows(VaultwardenPropertyResolutionException.class,
                () -> resolver().resolve("app.x", "bao:"));
        assertEquals(0, aufrufe.get());
    }

    @Test
    @DisplayName("Client meldet transient/definitiv korrekt zurueck")
    void fehlerklassifizierung() {
        OpenBaoClient c = client();
        status = 404;
        assertEquals(Optional.empty(), c.lies("weg", "value"));
        assertFalse(c.letzterFehlerWarTransient());

        status = 500;
        assertEquals(Optional.empty(), c.lies("weg", "value"));
        assertTrue(c.letzterFehlerWarTransient());
    }
}
