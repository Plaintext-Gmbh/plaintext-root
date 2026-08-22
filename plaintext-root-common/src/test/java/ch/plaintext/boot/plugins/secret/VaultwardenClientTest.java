/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import javax.crypto.Cipher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Unit-Tests fuer {@link VaultwardenClient} OHNE echtes Netz: ein winziger, lokal an
 * {@code 127.0.0.1} gebundener {@link HttpServer} spielt Vaultwarden (prelogin, token, sync,
 * ciphers-GET/PUT). Die Antwort-Payloads werden mit den <b>Produktions</b>-Krypto-Primitiven
 * ({@link VaultwardenCrypto#encryptSymmetric}, PBKDF2, HKDF, RSA-OAEP-SHA1) erzeugt, sodass der
 * Client sie mit demselben Verfahren wieder entschluesseln muss — echte Verhaltenspruefung des
 * Login-/Sync-/Rotation-Flows, kein Coverage-Gaming.
 */
class VaultwardenClientTest {

    private static final SecureRandom RND = new SecureRandom();
    private static final String EMAIL = "svc@plaintext.ch";
    private static final String MASTER_PW = "master-pw-123";
    private static final int ITERATIONS = 200; // klein -> schnelle Tests (Mock validiert Hash nicht)
    private static final String ORG_ID = "11111111-1111-1111-1111-111111111111";

    private MockVault vault;

    // --- Krypto-Fixture (deterministisch je Test neu) ---
    private byte[] userKey;      // 64B – persoenlicher Schluessel
    private byte[] orgKey;       // 64B – Org-Schluessel
    private byte[] personalItemKey; // 64B – per-Cipher-Key des persoenlichen Items
    private KeyPair rsaKeyPair;

    @BeforeEach
    void setUp() throws IOException {
        vault = new MockVault();
        userKey = randomBytes(64);
        orgKey = randomBytes(64);
        personalItemKey = randomBytes(64);
        rsaKeyPair = generateRsa();
        vault.preloginBody = "{\"kdf\":0,\"kdfIterations\":" + ITERATIONS + "}";
        vault.tokenBody = tokenJson(3600);
        vault.syncBody = syncJson(true);
    }

    @AfterEach
    void tearDown() {
        vault.close();
    }

    // ------------------------------------------------------------------
    // Voller Lese-Flow: Login -> Sync -> Entschluesselung
    // ------------------------------------------------------------------

    @Test
    void getItems_fullFlow_decryptsPersonalAndOrgItems() {
        VaultwardenClient client = newClient(props());

        List<VaultwardenItem> items = client.getItems();

        assertThat(items).hasSize(2); // persoenlich + org; das Nicht-Login-Cipher (type 2) faellt raus
        VaultwardenItem personal = byName(items, "Personal Item");
        assertThat(personal).isNotNull();
        assertThat(personal.username()).isEqualTo("alice");
        assertThat(personal.password()).isEqualTo("s3cr3t-personal");
        assertThat(personal.field("apikey")).contains("KEY-123");

        VaultwardenItem org = byName(items, "Org Item");
        assertThat(org).isNotNull();
        assertThat(org.username()).isEqualTo("bob");
        assertThat(org.password()).isEqualTo("s3cr3t-org");
    }

    @Test
    void getItems_configuredDeviceIdentifier_stillDecrypts() {
        VaultwardenProperties p = props();
        p.setDeviceIdentifier("fixed-device-uuid");
        p.setDeviceName("plaintext"); // gleich wie appName-Default -> deckt base.equals(app)-Zweig
        VaultwardenClient client = new VaultwardenClient(p, "plaintext");

        assertThat(client.getItems()).hasSize(2);
        assertThat(vault.tokenCalls).isEqualTo(1);
    }

    @Test
    void getItems_isCachedWithinTtl_noSecondLoginOrSync() {
        VaultwardenClient client = newClient(props());

        client.getItems();
        client.getItems();
        client.getItems();

        assertThat(vault.tokenCalls).isEqualTo(1);
        assertThat(vault.syncCalls).isEqualTo(1);
    }

    @Test
    void invalidate_forcesResyncButNoRelogin() {
        VaultwardenClient client = newClient(props());
        client.getItems();
        assertThat(vault.syncCalls).isEqualTo(1);

        client.invalidate();
        client.getItems();

        assertThat(vault.syncCalls).isEqualTo(2); // erneuter Sync
        assertThat(vault.tokenCalls).isEqualTo(1); // aber KEIN erneuter Login (userKey noch gueltig)
    }

    @Test
    void getItems_clientCredentialsGrant_decrypts() {
        VaultwardenProperties p = props();
        p.setClientId("user.abc");
        p.setClientSecret("supersecret");
        VaultwardenClient client = newClient(p);

        assertThat(client.getItems()).hasSize(2);
        assertThat(vault.tokenCalls).isEqualTo(1);
    }

    @Test
    void getItems_syncWithoutPrivateKeyAndOrgs_onlyPersonalItems() {
        vault.syncBody = syncJson(false); // kein PrivateKey, keine Orgs, nur persoenliches Item
        VaultwardenClient client = newClient(props());

        List<VaultwardenItem> items = client.getItems();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).name()).isEqualTo("Personal Item");
    }

    // ------------------------------------------------------------------
    // Fail-safe-Pfade (nie Exception nach aussen, immer Liste)
    // ------------------------------------------------------------------

    @Test
    void getItems_preloginError_failsSafeEmpty() {
        vault.preloginStatus = 500;
        vault.preloginBody = "boom";
        assertThat(newClient(props()).getItems()).isEmpty();
    }

    @Test
    void getItems_tokenError_failsSafeEmpty() {
        vault.tokenStatus = 401;
        vault.tokenBody = "{\"error\":\"invalid_grant\"}";
        assertThat(newClient(props()).getItems()).isEmpty();
    }

    /**
     * Karte 395: Nach einem HTTP 429 darf NICHT sofort erneut eingeloggt werden.
     *
     * <p>Genau das war die Ursache der Kaskade vom 01.08.2026: Der Fehlerpfad setzte die
     * Cache-Gueltigkeit auf hoechstens 60 Sekunden, danach lief der naechste Login-Versuch — und
     * hielt damit das Rate-Limit am Leben, das er abwarten wollte. Eine einzige Instanz im
     * Crashloop konnte so den Start ANDERER Anwendungen verhindern.</p>
     */
    @Test
    void getItems_rateLimit429_versuchtNichtSofortErneut() {
        // cacheTtl = 1s mit Absicht: VOR dem Fix war der Fehler-Backoff clamp(cacheTtl, 1, 60),
        // also hier 1 Sekunde — nach dem Warten unten waere der naechste Login rausgegangen und
        // der Test rot. Mit dem Fix greift bei einem 429 der lange Backoff, unabhaengig vom TTL.
        VaultwardenProperties p = props();
        p.setCacheTtlSeconds(1);
        vault.tokenStatus = 429;
        vault.tokenBody = "{\"message\":\"Too many login requests\"}";
        TestUhr uhr = new TestUhr();
        VaultwardenClient client = newClient(p, uhr);

        assertThat(client.getItems()).isEmpty();
        assertThat(vault.tokenCalls).isEqualTo(1);

        uhr.weiter(Duration.ofMillis(1300));   // laenger als der alte Backoff
        client.getItems();
        client.getItems();

        assertThat(vault.tokenCalls)
                .as("nach einem 429 darf kein weiterer Login-Versuch rausgehen — jeder haelt die Sperre am Leben")
                .isEqualTo(1);
    }

    /**
     * Auch ein gewoehnlicher Fehlschlag fuehrt nicht zu Dauerfeuer: Der Backoff startet bei 30
     * Sekunden und verdoppelt sich — vor dem Fix waren es hoechstens 60, danach ging es endlos
     * im selben Takt weiter.
     */
    @Test
    void getItems_fehlschlag_versuchtNichtSofortErneut() {
        VaultwardenProperties p = props();
        p.setCacheTtlSeconds(1);
        vault.tokenStatus = 500;
        vault.tokenBody = "boom";
        TestUhr uhr = new TestUhr();
        VaultwardenClient client = newClient(p, uhr);

        assertThat(client.getItems()).isEmpty();
        uhr.weiter(Duration.ofMillis(1300));
        client.getItems();

        assertThat(vault.tokenCalls)
                .as("Backoff greift auch ohne Rate-Limit")
                .isEqualTo(1);
    }

    /** Nach einem erfolgreichen Zugriff ist der Fehlerzaehler zurueckgesetzt (kein Dauer-Backoff). */
    @Test
    void getItems_nachErfolgKeinBackoffMehr() {
        vault.tokenStatus = 429;
        vault.tokenBody = "{\"message\":\"Too many login requests\"}";
        VaultwardenClient client = newClient(props());
        assertThat(client.getItems()).isEmpty();

        // Vaultwarden ist wieder da; der Cache wird verworfen, wie es die Rotation auch tut.
        vault.tokenStatus = 200;
        vault.tokenBody = tokenJson(3600);
        client.invalidate();

        assertThat(client.getItems()).hasSize(2);
        assertThat(vault.tokenCalls).isEqualTo(2);
    }

    @Test
    void getItems_syncError_failsSafeEmpty() {
        vault.syncStatus = 500;
        vault.syncBody = "boom";
        VaultwardenClient client = newClient(props());
        assertThat(client.getItems()).isEmpty();
        assertThat(vault.tokenCalls).isEqualTo(1); // Login klappte, erst Sync scheiterte
    }

    // ------------------------------------------------------------------
    // Diagnose-Flags fuer den Boot-Retry (Vorfaelle 18.+21.08.2026)
    // ------------------------------------------------------------------

    /** Nach einem 429 stehen die Flags auf transient+Rate-Limit; nach Erfolg sind sie geloescht. */
    @Test
    void fehlerFlags_nach429GesetztNachErfolgGeloescht() {
        vault.tokenStatus = 429;
        vault.tokenBody = "{\"message\":\"Too many login requests\"}";
        VaultwardenClient client = newClient(props());

        assertThat(client.getItems()).isEmpty();
        assertThat(client.istLetzterRefreshGescheitert()).isTrue();
        assertThat(client.warLetzterFehlerRateLimit()).isTrue();
        assertThat(client.letzteFehlermeldung()).contains("429");

        vault.tokenStatus = 200;
        vault.tokenBody = tokenJson(3600);
        client.invalidate();

        assertThat(client.getItems()).hasSize(2);
        assertThat(client.istLetzterRefreshGescheitert()).isFalse();
        assertThat(client.warLetzterFehlerRateLimit()).isFalse();
        assertThat(client.letzteFehlermeldung()).isEmpty();
    }

    /** Ein gewoehnlicher Fehlschlag ist transient, aber KEIN Rate-Limit. */
    @Test
    void fehlerFlags_gewoehnlicherFehlerOhneRateLimit() {
        vault.tokenStatus = 500;
        vault.tokenBody = "boom";
        VaultwardenClient client = newClient(props());

        assertThat(client.getItems()).isEmpty();
        assertThat(client.istLetzterRefreshGescheitert()).isTrue();
        assertThat(client.warLetzterFehlerRateLimit()).isFalse();
        assertThat(client.letzteFehlermeldung()).isNotEmpty();
    }

    @Test
    void getItems_unsupportedKdf_failsSafeEmpty() {
        vault.preloginBody = "{\"kdf\":1,\"kdfIterations\":3}"; // Argon2 -> nicht unterstuetzt
        assertThat(newClient(props()).getItems()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Rotation (Schreib-Flow)
    // ------------------------------------------------------------------

    @Test
    void rotatePassword_success_writesNewEncryptedPasswordAndInvalidatesCache() throws Exception {
        vault.cipherGetBody = personalCipherJson(); // GET /api/ciphers/{id}
        vault.cipherPutStatus = 200;
        VaultwardenClient client = newClient(props());
        client.getItems(); // Cache fuellen, damit findByName greift

        boolean ok = client.rotatePassword("Personal", "n3w-P@ss");

        assertThat(ok).isTrue();
        // Das gesendete CipherRequestModel enthaelt das neu verschluesselte Passwort ...
        JsonObject put = JsonParser.parseString(vault.lastPutBody).getAsJsonObject();
        String newPwEnc = put.getAsJsonObject("login").get("password").getAsString();
        byte[] decrypted = VaultwardenCrypto.decryptSymmetric(EncString.parse(newPwEnc), personalItemKey);
        assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo("n3w-P@ss");
        // ... und alle uebrigen Felder bleiben inhaltlich UNVERAENDERT (der Username entschluesselt
        // weiterhin zu "alice"). Kein Vergleich des exakten Chiffretexts: AES-256-CBC nutzt einen
        // zufaelligen IV, jede Verschluesselung von "alice" ergibt daher einen anderen String.
        String newUserEnc = put.getAsJsonObject("login").get("username").getAsString();
        byte[] decryptedUser = VaultwardenCrypto.decryptSymmetric(EncString.parse(newUserEnc), personalItemKey);
        assertThat(new String(decryptedUser, StandardCharsets.UTF_8)).isEqualTo("alice");
        assertThat(put.getAsJsonArray("fields")).isNotNull();
        assertThat(put.get("lastKnownRevisionDate").getAsString()).isEqualTo("2024-01-01T00:00:00.000Z");

        // Nach erfolgreichem PUT ist der Lese-Cache invalidiert -> erneuter Sync beim naechsten Read.
        client.getItems();
        assertThat(vault.syncCalls).isEqualTo(2);
    }

    @Test
    void rotatePassword_blankInputs_returnFalse() {
        VaultwardenClient client = newClient(props());
        assertThat(client.rotatePassword(null, "x")).isFalse();
        assertThat(client.rotatePassword("  ", "x")).isFalse();
        assertThat(client.rotatePassword("Personal", null)).isFalse();
    }

    @Test
    void rotatePassword_itemNotFound_returnsFalse() {
        VaultwardenClient client = newClient(props());
        client.getItems();
        assertThat(client.rotatePassword("Does Not Exist", "x")).isFalse();
    }

    @Test
    void rotatePassword_cipherGetFails_returnsFalse() {
        vault.cipherGetStatus = 404;
        vault.cipherGetBody = "not found";
        VaultwardenClient client = newClient(props());
        client.getItems();
        assertThat(client.rotatePassword("Personal", "x")).isFalse();
    }

    @Test
    void rotatePassword_putFails_returnsFalseAndKeepsCache() {
        vault.cipherGetBody = personalCipherJson();
        vault.cipherPutStatus = 500;
        VaultwardenClient client = newClient(props());
        client.getItems();

        assertThat(client.rotatePassword("Personal", "x")).isFalse();

        client.getItems(); // Cache NICHT invalidiert -> kein zweiter Sync
        assertThat(vault.syncCalls).isEqualTo(1);
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private VaultwardenProperties props() {
        VaultwardenProperties p = new VaultwardenProperties();
        p.setEnabled(true);
        p.setUrl(vault.url() + "/"); // trailing slash -> deckt trimUrl()
        p.setEmail(EMAIL);
        p.setMasterPassword(MASTER_PW);
        p.setCacheTtlSeconds(300);
        p.setLoginTtlMinutes(30);
        p.setHttpTimeoutSeconds(5);
        return p;
    }

    private static VaultwardenClient newClient(VaultwardenProperties p) {
        return new VaultwardenClient(p, "junit");
    }

    /**
     * Steuerbare Uhr fuer die Backoff-Tests (Karte 608).
     *
     * <p>Vorher warteten diese Tests mit {@code Thread.sleep(1300)} echte Zeit ab, um einen
     * Backoff zu ueberspringen. Das ist doppelt schlecht: Auf einem langsamen Runner kippt der
     * Test, auf einem schnellen kostet er trotzdem die volle Wartezeit. Sonar meldet es als
     * java:S2925.</p>
     *
     * <p>Mit dieser Uhr wird die Zeit gesetzt statt abgewartet — der Test prueft dasselbe
     * Verhalten in Millisekunden statt in Sekunden.</p>
     */
    private static final class TestUhr extends Clock {
        private Instant jetzt = Instant.parse("2026-01-01T00:00:00Z");

        @Override public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return jetzt;
        }

        /** Stellt die Uhr vor — der Ersatz fuer {@code Thread.sleep}. */
        void weiter(Duration d) {
            jetzt = jetzt.plus(d);
        }
    }

    private static VaultwardenClient newClient(VaultwardenProperties p, Clock uhr) {
        return new VaultwardenClient(p, "junit", uhr);
    }

    private static VaultwardenItem byName(List<VaultwardenItem> items, String name) {
        return items.stream().filter(i -> name.equals(i.name())).findFirst().orElse(null);
    }

    /** {@code encryptSymmetric} der Produktion – so, wie der echte Vaultwarden es liefern wuerde. */
    private static String encWith(byte[] key64, String plaintext) {
        return VaultwardenCrypto.encryptSymmetric(plaintext.getBytes(StandardCharsets.UTF_8), key64);
    }

    private String tokenJson(long expiresIn) {
        byte[] masterKey = VaultwardenCrypto.pbkdf2Sha256(
                MASTER_PW.getBytes(StandardCharsets.UTF_8),
                EMAIL.getBytes(StandardCharsets.UTF_8), ITERATIONS, 32);
        byte[] stretched = VaultwardenCrypto.stretchMasterKey(masterKey);
        String encUserKey = VaultwardenCrypto.encryptSymmetric(userKey, stretched);
        JsonObject tok = new JsonObject();
        tok.addProperty("access_token", "test-access-token");
        tok.addProperty("Key", encUserKey);
        tok.addProperty("expires_in", expiresIn);
        return tok.toString();
    }

    private String syncJson(boolean withPrivateKeyAndOrg) {
        JsonObject profile = new JsonObject();
        if (withPrivateKeyAndOrg) {
            byte[] pkcs8 = rsaKeyPair.getPrivate().getEncoded();
            profile.addProperty("PrivateKey", encBytes(userKey, pkcs8));
            JsonArray orgs = new JsonArray();
            JsonObject org = new JsonObject();
            org.addProperty("Id", ORG_ID);
            org.addProperty("Key", rsaEncrypt(orgKey, rsaKeyPair.getPublic()));
            orgs.add(org);
            profile.add("Organizations", orgs);
        }

        JsonArray ciphers = new JsonArray();
        ciphers.add(personalCipher());
        if (withPrivateKeyAndOrg) {
            ciphers.add(orgCipher());
            ciphers.add(nonLoginCipher());
        }

        JsonObject root = new JsonObject();
        root.add("Profile", profile);
        root.add("Ciphers", ciphers);
        return root.toString();
    }

    /** Persoenliches Login-Cipher mit per-Cipher-Key (moderne Bitwarden-Ciphers). */
    private JsonObject personalCipher() {
        JsonObject c = new JsonObject();
        c.addProperty("Id", "cipher-personal");
        c.addProperty("Type", 1);
        c.addProperty("Key", encWith(userKey, dummy())); // wird gleich ueberschrieben
        // per-Cipher-Key korrekt setzen: itemKey mit userKey verschluesselt
        c.addProperty("Key", encBytes(userKey, personalItemKey));
        c.addProperty("Name", encWith(personalItemKey, "Personal Item"));
        JsonObject login = new JsonObject();
        login.addProperty("Username", encWith(personalItemKey, "alice"));
        login.addProperty("Password", encWith(personalItemKey, "s3cr3t-personal"));
        c.add("Login", login);
        JsonArray fields = new JsonArray();
        JsonObject f = new JsonObject();
        f.addProperty("Type", 0);
        f.addProperty("Name", encWith(personalItemKey, "apikey"));
        f.addProperty("Value", encWith(personalItemKey, "KEY-123"));
        fields.add(f);
        c.add("Fields", fields);
        return c;
    }

    /** Org-Login-Cipher OHNE per-Cipher-Key: direkt mit dem Org-Schluessel verschluesselt. */
    private JsonObject orgCipher() {
        JsonObject c = new JsonObject();
        c.addProperty("Id", "cipher-org");
        c.addProperty("Type", 1);
        c.addProperty("OrganizationId", ORG_ID);
        c.addProperty("Name", encWith(orgKey, "Org Item"));
        JsonObject login = new JsonObject();
        login.addProperty("Username", encWith(orgKey, "bob"));
        login.addProperty("Password", encWith(orgKey, "s3cr3t-org"));
        c.add("Login", login);
        return c;
    }

    /** Nicht-Login-Cipher (type 2 = SecureNote) -> wird uebersprungen. */
    private static JsonObject nonLoginCipher() {
        JsonObject c = new JsonObject();
        c.addProperty("Id", "cipher-note");
        c.addProperty("Type", 2);
        return c;
    }

    /** Roh-Cipher wie von {@code GET /api/ciphers/{id}} – mit uris + revisionDate fuer die Rotation. */
    private String personalCipherJson() {
        JsonObject c = personalCipher();
        c.addProperty("Favorite", true);
        c.addProperty("Reprompt", 0);
        c.addProperty("RevisionDate", "2024-01-01T00:00:00.000Z");
        JsonObject login = c.getAsJsonObject("Login");
        login.addProperty("Totp", encWith(personalItemKey, "otpauth://x"));
        JsonArray uris = new JsonArray();
        JsonObject u = new JsonObject();
        u.addProperty("Uri", encWith(personalItemKey, "https://example.org"));
        u.addProperty("Match", 0);
        uris.add(u);
        login.add("Uris", uris);
        return c.toString();
    }

    private static String dummy() {
        return "x";
    }

    private static String encBytes(byte[] key64, byte[] data) {
        return VaultwardenCrypto.encryptSymmetric(data, key64);
    }

    /** RSA-OAEP-SHA1 (EncString type 4) – exakt das, was {@code decryptRsaOaepSha1} rueckgaengig macht. */
    private static String rsaEncrypt(byte[] data, PublicKey pub) {
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, pub);
            return "4." + java.util.Base64.getEncoder().encodeToString(cipher.doFinal(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair generateRsa() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            return kpg.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RND.nextBytes(b);
        return b;
    }

    // ------------------------------------------------------------------
    // Winziger lokaler Vaultwarden-Doppelgaenger (nur 127.0.0.1)
    // ------------------------------------------------------------------
    private static final class MockVault implements AutoCloseable {
        private final HttpServer server;
        private final int port;

        int preloginStatus = 200;
        String preloginBody;
        int tokenStatus = 200;
        String tokenBody;
        int syncStatus = 200;
        String syncBody;
        int cipherGetStatus = 200;
        String cipherGetBody = "{}";
        int cipherPutStatus = 200;

        volatile String lastPutBody;
        volatile int tokenCalls;
        volatile int syncCalls;

        MockVault() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            port = server.getAddress().getPort();
            server.createContext("/", this::dispatch);
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + port;
        }

        private void dispatch(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            byte[] reqBody = ex.getRequestBody().readAllBytes();
            if (path.endsWith("/accounts/prelogin")) {
                respond(ex, preloginStatus, preloginBody);
            } else if (path.endsWith("/connect/token")) {
                tokenCalls++;
                respond(ex, tokenStatus, tokenBody);
            } else if (path.startsWith("/api/sync")) {
                syncCalls++;
                respond(ex, syncStatus, syncBody);
            } else if (path.startsWith("/api/ciphers/")) {
                if ("PUT".equals(method)) {
                    lastPutBody = new String(reqBody, StandardCharsets.UTF_8);
                    respond(ex, cipherPutStatus, cipherPutStatus < 300 ? "{}" : "err");
                } else {
                    respond(ex, cipherGetStatus, cipherGetBody);
                }
            } else {
                respond(ex, 404, "{}");
            }
        }

        private static void respond(HttpExchange ex, int status, String body) throws IOException {
            byte[] b = (body == null ? "" : body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            }
            ex.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
