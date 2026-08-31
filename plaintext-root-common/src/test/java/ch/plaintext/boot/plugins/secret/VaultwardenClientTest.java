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
 * Unit tests for {@link VaultwardenClient} WITHOUT a real network: a tiny {@link HttpServer}
 * bound locally to {@code 127.0.0.1} plays Vaultwarden (prelogin, token, sync,
 * ciphers GET/PUT). The response payloads are produced with the <b>production</b> crypto
 * primitives ({@link VaultwardenCrypto#encryptSymmetric}, PBKDF2, HKDF, RSA-OAEP-SHA1), so that
 * the client has to decrypt them again with the same procedure — a real behavioural check of
 * the login/sync/rotation flow, no coverage gaming.
 */
class VaultwardenClientTest {

    private static final SecureRandom RND = new SecureRandom();
    private static final String EMAIL = "svc@plaintext.ch";
    private static final String MASTER_PW = "master-pw-123";
    private static final int ITERATIONS = 200; // small -> fast tests (the mock does not validate the hash)
    private static final String ORG_ID = "11111111-1111-1111-1111-111111111111";

    private MockVault vault;

    // --- crypto fixture (deterministic, fresh for every test) ---
    private byte[] userKey;      // 64B – personal key
    private byte[] orgKey;       // 64B – org key
    private byte[] personalItemKey; // 64B – per-cipher key of the personal item
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
    // Full read flow: login -> sync -> decryption
    // ------------------------------------------------------------------

    @Test
    void getItems_fullFlow_decryptsPersonalAndOrgItems() {
        VaultwardenClient client = newClient(props());

        List<VaultwardenItem> items = client.getItems();

        assertThat(items).hasSize(2); // personal + org; the non-login cipher (type 2) drops out
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
        p.setDeviceName("plaintext"); // same as the appName default -> covers the base.equals(app) branch
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

        assertThat(vault.syncCalls).isEqualTo(2); // another sync
        assertThat(vault.tokenCalls).isEqualTo(1); // but NO new login (the userKey is still valid)
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
        vault.syncBody = syncJson(false); // no private key, no orgs, only the personal item
        VaultwardenClient client = newClient(props());

        List<VaultwardenItem> items = client.getItems();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).name()).isEqualTo("Personal Item");
    }

    // ------------------------------------------------------------------
    // Fail-safe paths (never an exception to the outside, always a list)
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
     * Karte 395: after an HTTP 429 there must NOT be an immediate new login.
     *
     * <p>That was exactly the cause of the cascade of 01.08.2026: the error path set the
     * cache validity to at most 60 seconds, after which the next login attempt ran — and
     * thereby kept alive the very rate limit it wanted to wait out. A single instance in a
     * crash loop could prevent OTHER applications from starting that way.</p>
     */
    @Test
    void getItems_rateLimit429_versuchtNichtSofortErneut() {
        // cacheTtl = 1s on purpose: BEFORE the fix the error backoff was clamp(cacheTtl, 1, 60),
        // so one second here — after the wait below the next login would have gone out and
        // the test would be red. With the fix the long backoff applies on a 429, whatever the TTL.
        VaultwardenProperties p = props();
        p.setCacheTtlSeconds(1);
        vault.tokenStatus = 429;
        vault.tokenBody = "{\"message\":\"Too many login requests\"}";
        TestUhr uhr = new TestUhr();
        VaultwardenClient client = newClient(p, uhr);

        assertThat(client.getItems()).isEmpty();
        assertThat(vault.tokenCalls).isEqualTo(1);

        uhr.weiter(Duration.ofMillis(1300));   // longer than the old backoff
        client.getItems();
        client.getItems();

        assertThat(vault.tokenCalls)
                .as("nach einem 429 darf kein weiterer Login-Versuch rausgehen — jeder haelt die Sperre am Leben")
                .isEqualTo(1);
    }

    /**
     * An ordinary failure does not lead to continuous fire either: the backoff starts at 30
     * seconds and doubles — before the fix it was at most 60, after which it went on endlessly
     * at the same rate.
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

    /** After a successful access the error counter is reset (no permanent backoff). */
    @Test
    void getItems_nachErfolgKeinBackoffMehr() {
        vault.tokenStatus = 429;
        vault.tokenBody = "{\"message\":\"Too many login requests\"}";
        VaultwardenClient client = newClient(props());
        assertThat(client.getItems()).isEmpty();

        // Vaultwarden is back; the cache is discarded, just as the rotation does it.
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
        assertThat(vault.tokenCalls).isEqualTo(1); // the login worked, only the sync failed
    }

    // ------------------------------------------------------------------
    // Diagnostic flags for the boot retry (incidents 18.+21.08.2026)
    // ------------------------------------------------------------------

    /** After a 429 the flags stand at transient+rate limit; after a success they are cleared. */
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

    /** An ordinary failure is transient, but NOT a rate limit. */
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
        vault.preloginBody = "{\"kdf\":1,\"kdfIterations\":3}"; // Argon2 -> not supported
        assertThat(newClient(props()).getItems()).isEmpty();
    }

    // ------------------------------------------------------------------
    // Rotation (write flow)
    // ------------------------------------------------------------------

    @Test
    void rotatePassword_success_writesNewEncryptedPasswordAndInvalidatesCache() throws Exception {
        vault.cipherGetBody = personalCipherJson(); // GET /api/ciphers/{id}
        vault.cipherPutStatus = 200;
        VaultwardenClient client = newClient(props());
        client.getItems(); // fill the cache so that findByName works

        boolean ok = client.rotatePassword("Personal", "n3w-P@ss");

        assertThat(ok).isTrue();
        // The CipherRequestModel that is sent contains the newly encrypted password ...
        JsonObject put = JsonParser.parseString(vault.lastPutBody).getAsJsonObject();
        String newPwEnc = put.getAsJsonObject("login").get("password").getAsString();
        byte[] decrypted = VaultwardenCrypto.decryptSymmetric(EncString.parse(newPwEnc), personalItemKey);
        assertThat(new String(decrypted, StandardCharsets.UTF_8)).isEqualTo("n3w-P@ss");
        // ... and all the other fields stay UNCHANGED in content (the username still decrypts
        // to "alice"). No comparison of the exact ciphertext: AES-256-CBC uses a
        // random IV, so every encryption of "alice" yields a different string.
        String newUserEnc = put.getAsJsonObject("login").get("username").getAsString();
        byte[] decryptedUser = VaultwardenCrypto.decryptSymmetric(EncString.parse(newUserEnc), personalItemKey);
        assertThat(new String(decryptedUser, StandardCharsets.UTF_8)).isEqualTo("alice");
        assertThat(put.getAsJsonArray("fields")).isNotNull();
        assertThat(put.get("lastKnownRevisionDate").getAsString()).isEqualTo("2024-01-01T00:00:00.000Z");

        // After a successful PUT the read cache is invalidated -> another sync at the next read.
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

        client.getItems(); // cache NOT invalidated -> no second sync
        assertThat(vault.syncCalls).isEqualTo(1);
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private VaultwardenProperties props() {
        VaultwardenProperties p = new VaultwardenProperties();
        p.setEnabled(true);
        p.setUrl(vault.url() + "/"); // trailing slash -> covers trimUrl()
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
     * Controllable clock for the backoff tests (Karte 608).
     *
     * <p>Previously these tests waited out real time with {@code Thread.sleep(1300)} in order to
     * skip a backoff. That is bad twice over: on a slow runner the test tips over, on a fast one
     * it costs the full waiting time anyway. Sonar reports it as java:S2925.</p>
     *
     * <p>With this clock the time is set instead of waited out — the test checks the same
     * behaviour in milliseconds instead of seconds.</p>
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

        /** Moves the clock forward — the replacement for {@code Thread.sleep}. */
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

    /** The production {@code encryptSymmetric} – just as the real Vaultwarden would deliver it. */
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

    /** Personal login cipher with a per-cipher key (modern Bitwarden ciphers). */
    private JsonObject personalCipher() {
        JsonObject c = new JsonObject();
        c.addProperty("Id", "cipher-personal");
        c.addProperty("Type", 1);
        c.addProperty("Key", encWith(userKey, dummy())); // is overwritten right away
        // set the per-cipher key correctly: itemKey encrypted with the userKey
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

    /** Org login cipher WITHOUT a per-cipher key: encrypted directly with the org key. */
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

    /** Non-login cipher (type 2 = SecureNote) -> is skipped. */
    private static JsonObject nonLoginCipher() {
        JsonObject c = new JsonObject();
        c.addProperty("Id", "cipher-note");
        c.addProperty("Type", 2);
        return c;
    }

    /** Raw cipher as from {@code GET /api/ciphers/{id}} – with uris + revisionDate for the rotation. */
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

    /** RSA-OAEP-SHA1 (EncString type 4) – exactly what {@code decryptRsaOaepSha1} undoes. */
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
    // Tiny local Vaultwarden stand-in (127.0.0.1 only)
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
