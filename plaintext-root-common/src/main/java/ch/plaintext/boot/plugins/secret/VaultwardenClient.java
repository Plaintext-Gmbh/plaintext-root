/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import lombok.extern.slf4j.Slf4j;

/**
 * HTTP and crypto orchestration of the Bitwarden/Vaultwarden flow.
 *
 * <p>Keeps the userKey, the orgKeys and the decrypted login items in memory
 * (with a TTL); logs in again on expiry and re-synchronizes. Thread-safe through
 * a single {@code synchronized ensureFresh()}. ALL errors are fail-safe: they are
 * logged (never secret values) and processing continues with empty data — the app
 * boot must never fail because of it.</p>
 *
 * <p>Only JDK/JCA + Gson — no BouncyCastle (KDF=0 = PBKDF2).</p>
 */
@Slf4j
class VaultwardenClient {

    // Reused JSON/form keys and HTTP headers (tolerant of Pascal/camelCase).
    private static final String ORGANIZATION_ID_PASCAL = "OrganizationId";
    private static final String ORGANIZATION_ID = "organizationId";
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String MEDIA_TYPE_JSON = "application/json";
    private static final String KEY_LOGIN = "login";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_FIELDS = "fields";
    private static final String KEY_VALUE = "value";

    private final VaultwardenProperties props;
    private final HttpClient http;
    /**
     * STABLE device UUID: derived deterministically from email + app name (or the explicitly
     * configured value). Stays the same across restarts/redeploys, so that Vaultwarden does not
     * rate the login as a "new device" and does not send a "New Device Logged In" mail.
     */
    private final String deviceIdentifier;
    /** Display name of the device ({@code deviceName}), including the app name to tell them apart in the vault. */
    private final String deviceName;

    // --- cached, decrypted state (guarded by this) ---
    private byte[] userKey;                       // 64B (enc32||mac32)
    private String accessToken;
    private final Map<String, byte[]> orgKeys = new HashMap<>();
    private List<VaultwardenItem> cachedItems = null;
    private Instant loginExpiry = Instant.EPOCH;
    private Instant cacheExpiry = Instant.EPOCH;

    /**
     * Number of consecutive failed Vaultwarden accesses — drives the backoff
     * (Karte 395). Reset to 0 on every success.
     */
    private int fehlversucheInFolge = 0;

    // --- diagnosis of the last refresh attempt (for the boot retry in the resolver) ---------
    // Without this distinction a consumer only sees an empty item list after a failure and
    // cannot tell "Vaultwarden was momentarily unreachable/429" apart from "that item
    // really does not exist" — that is exactly where the deploy diagnosis failed on
    // 21.08.2026 (guild 1.372.0 / app snapshot-dev).

    /** {@code true} when the LAST login/sync attempt failed (transient). */
    private boolean letzterRefreshGescheitert = false;
    /** {@code true} when the last failure was a recognized rate limit (HTTP 429). */
    private boolean letzterFehlerWarRateLimit = false;
    /** Secret-free message of the last failure (for the log and the fail-fast exception). */
    private String letzteFehlermeldung = "";

    /** Backoff after the first failure; doubles with every further one up to {@link #BACKOFF_MAX_SEK}. */
    private static final int BACKOFF_START_SEK = 30;
    /** Upper bound of the backoff — 15 minutes. After that attempts continue periodically, but rarely. */
    private static final int BACKOFF_MAX_SEK = 900;
    /**
     * Backoff after an HTTP 429. Considerably longer than the normal entry value: a rate limit
     * only expires if it is NOT fed any further — every attempt during the block extends it.
     */
    private static final int BACKOFF_RATE_LIMIT_SEK = 300;

    /**
     * Time source for cache expiry and backoff.
     *
     * <p>Karte 608: previously {@code Instant.now()} was used everywhere here. The tests
     * therefore had to let real time pass with {@code Thread.sleep(1300)} in order to skip a
     * backoff — two seconds of waiting per run, and Sonar rightly reports {@code Thread.sleep}
     * in tests (java:S2925): a test that waits for the clock is flaky on a slow runner and slow
     * on a fast one.
     *
     * <p>In production it is unchanged {@link Clock#systemUTC()}; only the test injects a clock
     * of its own and moves it forward.</p>
     */
    private final Clock clock;

    VaultwardenClient(VaultwardenProperties props, String appName) {
        this(props, appName, Clock.systemUTC());
    }

    VaultwardenClient(VaultwardenProperties props, String appName, Clock clock) {
        this.clock = clock;
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(5, props.getHttpTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String app = appName == null || appName.isBlank() ? "plaintext" : appName.trim();
        // Explicitly configured? otherwise derive it stably from email+app (not random → no device mails).
        String configured = props.getDeviceIdentifier();
        this.deviceIdentifier = configured != null && !configured.isBlank()
                ? configured.trim()
                : UUID.nameUUIDFromBytes(
                        ("plaintext-vault-device:" + safe(props.getEmail()) + ":" + app)
                                .getBytes(StandardCharsets.UTF_8)).toString();
        String base = props.getDeviceName() == null || props.getDeviceName().isBlank()
                ? "plaintext" : props.getDeviceName().trim();
        this.deviceName = base.equals(app) ? base : base + "-" + app;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Returns the currently decrypted login items (fail-safe: never {@code null},
     * empty on errors). Refreshes login/sync when the cache has expired.
     */
    synchronized List<VaultwardenItem> getItems() {
        ensureFresh();
        return cachedItems != null ? cachedItems : List.of();
    }

    /** Invalidates the cache (e.g. after a rotation). */
    synchronized void invalidate() {
        cachedItems = null;
        cacheExpiry = Instant.EPOCH;
    }

    // ------------------------------------------------------------------
    // Rotation (write direction) — Bitwarden-compatible PUT to the ciphers endpoint
    // ------------------------------------------------------------------

    /**
     * Sets a new password on the named login item (rotation) and leaves ALL other
     * fields untouched.
     *
     * <p>Procedure:</p>
     * <ol>
     *   <li>Ensure login + sync; find the item by its decrypted name
     *       (exact match, then contains, as in the read flow).</li>
     *   <li>Fetch the current cipher state via {@code GET /api/ciphers/{id}} and derive
     *       the matching key (per-cipher key via the org/user key).</li>
     *   <li>Replace only {@code login.password} using {@link VaultwardenCrypto#encryptSymmetric};
     *       pass all other EncString fields (name, username, uris, notes,
     *       fields, totp) through unchanged (they stay encrypted —
     *       NO re-encrypt, that would mean data loss).</li>
     *   <li>{@code PUT /api/ciphers/{id}} with the CipherRequestModel; on 2xx invalidate
     *       the cache and return {@code true}.</li>
     * </ol>
     *
     * <p>Fail-safe: on any error {@code false} + {@code log.warn} (never secrets);
     * no exception is passed to the outside.</p>
     */
    synchronized boolean rotatePassword(String itemName, String newPassword) {
        if (itemName == null || itemName.isBlank() || newPassword == null) {
            log.warn("Rotation abgelehnt: itemName/newPassword fehlt");
            return false;
        }
        try {
            ensureFresh();
            VaultwardenItem match = findByName(itemName);
            if (match == null || match.id() == null) {
                log.warn("Rotation: kein Login-Item passend zu '{}'", itemName);
                return false;
            }
            String baseUrl = trimUrl(props.getUrl());

            // fetch the current cipher state fresh (not from the TTL cache)
            JsonObject cipher = getCipher(baseUrl, match.id());
            if (cipher == null) {
                log.warn("Rotation: Cipher {} nicht abrufbar", match.id());
                return false;
            }

            // derive the matching key (identical to the read flow)
            String orgId = getStr(cipher, ORGANIZATION_ID_PASCAL, ORGANIZATION_ID);
            byte[] baseKey = (orgId != null) ? orgKeys.get(orgId) : userKey;
            if (baseKey == null) {
                log.warn("Rotation: kein Schluessel fuer Cipher {} (orgId={})", match.id(), orgId);
                return false;
            }
            byte[] itemKey = baseKey;
            String cipherKeyStr = getStr(cipher, "Key", "key");
            if (cipherKeyStr != null) {
                itemKey = VaultwardenCrypto.decryptSymmetric(EncString.parse(cipherKeyStr), baseKey);
            }

            // re-encrypt ONLY the password; pass everything else through
            String newPasswordEnc = VaultwardenCrypto.encryptSymmetric(
                    newPassword.getBytes(StandardCharsets.UTF_8), itemKey);
            JsonObject request = buildCipherRequest(cipher, newPasswordEnc);

            int status = putCipher(baseUrl, match.id(), request.toString());
            if (status >= 200 && status < 300) {
                invalidate();
                log.info("Rotation ok: Passwort von Item '{}' (Cipher {}) neu gesetzt", match.name(), match.id());
                return true;
            }
            log.warn("Rotation fehlgeschlagen: PUT /api/ciphers/{} HTTP {}", match.id(), status);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Rotation fuer Item '{}' fehlgeschlagen: {}", itemName, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("Rotation fuer Item '{}' fehlgeschlagen: {}", itemName, e.getMessage());
            return false;
        }
    }

    /** Finds a cached login item by its decrypted name (exact match, then contains). */
    private VaultwardenItem findByName(String itemName) {
        List<VaultwardenItem> items = cachedItems != null ? cachedItems : List.of();
        String needle = itemName.trim();
        for (VaultwardenItem i : items) {
            if (i.name() != null && i.name().equalsIgnoreCase(needle)) {
                return i;
            }
        }
        String needleLc = needle.toLowerCase(Locale.ROOT);
        for (VaultwardenItem i : items) {
            if (i.name() != null && i.name().toLowerCase(Locale.ROOT).contains(needleLc)) {
                return i;
            }
        }
        return null;
    }

    /** Fetches the current raw cipher via {@code GET /api/ciphers/{id}}. */
    private JsonObject getCipher(String baseUrl, String id) throws IOException, InterruptedException {
        HttpRequest req = baseRequest(baseUrl + "/api/ciphers/" + URLEncoder.encode(id, StandardCharsets.UTF_8))
                .header(HEADER_AUTHORIZATION, BEARER_PREFIX + accessToken)
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("ciphers-GET HTTP " + resp.statusCode()
                    + " (" + shortBody(resp.body()) + ")");
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    /** Sends the updated CipherRequestModel via {@code PUT /api/ciphers/{id}}. */
    private int putCipher(String baseUrl, String id, String body) throws Exception {
        HttpRequest req = baseRequest(baseUrl + "/api/ciphers/" + URLEncoder.encode(id, StandardCharsets.UTF_8))
                .header(HEADER_CONTENT_TYPE, MEDIA_TYPE_JSON)
                .header(HEADER_AUTHORIZATION, BEARER_PREFIX + accessToken)
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            log.warn("ciphers-PUT HTTP {} ({})", resp.statusCode(), shortBody(resp.body()));
        }
        return resp.statusCode();
    }

    /**
     * Builds the Bitwarden {@code CipherRequestModel} (camelCase) from the raw cipher and
     * replaces {@code login.password} and nothing else. All other EncString fields are taken
     * over unchanged (encrypted) — no field is re-encrypted.
     */
    private static JsonObject buildCipherRequest(JsonObject cipher, String newPasswordEnc) {
        JsonObject req = new JsonObject();
        req.addProperty("type", intOr(cipher, 1, "Type", "type"));
        req.add("name", jsonOrNull(getStr(cipher, "Name", "name")));
        req.add("notes", jsonOrNull(getStr(cipher, "Notes", "notes")));
        req.addProperty("favorite", boolOr(cipher, false, "Favorite", "favorite"));
        req.add("folderId", jsonOrNull(getStr(cipher, "FolderId", "folderId")));
        req.add(ORGANIZATION_ID, jsonOrNull(getStr(cipher, ORGANIZATION_ID_PASCAL, ORGANIZATION_ID)));
        req.addProperty("reprompt", intOr(cipher, 0, "Reprompt", "reprompt"));
        // pass the per-cipher key through unchanged (it is kept org/user-key encrypted)
        String cipherKey = getStr(cipher, "Key", "key");
        if (cipherKey != null) {
            req.addProperty("key", cipherKey);
        }
        // optimistic concurrency: send the current revision along
        String rev = getStr(cipher, "RevisionDate", "revisionDate");
        if (rev != null) {
            req.addProperty("lastKnownRevisionDate", rev);
        }
        req.add(KEY_LOGIN, buildLoginRequest(cipher, newPasswordEnc));
        JsonArray fields = buildFieldsRequest(cipher);
        if (fields != null) {
            req.add(KEY_FIELDS, fields);
        }
        return req;
    }

    /** Builds the {@code login} object: replace only {@code password}, pass the rest through encrypted. */
    private static JsonObject buildLoginRequest(JsonObject cipher, String newPasswordEnc) {
        JsonObject login = new JsonObject();
        JsonObject loginSrc = getObj(cipher, "Login", KEY_LOGIN);
        if (loginSrc == null) {
            login.addProperty(KEY_PASSWORD, newPasswordEnc);
            return login;
        }
        login.add(KEY_USERNAME, jsonOrNull(getStr(loginSrc, "Username", KEY_USERNAME)));
        login.addProperty(KEY_PASSWORD, newPasswordEnc);
        login.add("totp", jsonOrNull(getStr(loginSrc, "Totp", "totp")));
        JsonArray urisSrc = getArr(loginSrc, "Uris", "uris");
        if (urisSrc != null) {
            login.add("uris", buildUris(urisSrc));
        }
        return login;
    }

    /** Takes over the {@code uris} list (uri + match) unchanged. */
    private static JsonArray buildUris(JsonArray urisSrc) {
        JsonArray uris = new JsonArray();
        for (JsonElement el : urisSrc) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject u = el.getAsJsonObject();
            JsonObject nu = new JsonObject();
            nu.add("uri", jsonOrNull(getStr(u, "Uri", "uri")));
            JsonElement m = get(u, "Match", "match");
            nu.add("match", (m != null && m.isJsonPrimitive())
                    ? new JsonPrimitive(m.getAsInt()) : JsonNull.INSTANCE);
            uris.add(nu);
        }
        return uris;
    }

    /** Takes over the {@code fields} list (name/value) unchanged; {@code null} if there are none. */
    private static JsonArray buildFieldsRequest(JsonObject cipher) {
        JsonArray fieldsSrc = getArr(cipher, "Fields", KEY_FIELDS);
        if (fieldsSrc == null) {
            return null;
        }
        JsonArray fields = new JsonArray();
        for (JsonElement el : fieldsSrc) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject f = el.getAsJsonObject();
            JsonObject nf = new JsonObject();
            nf.addProperty("type", intOr(f, 0, "Type", "type"));
            nf.add("name", jsonOrNull(getStr(f, "Name", "name")));
            nf.add(KEY_VALUE, jsonOrNull(getStr(f, "Value", KEY_VALUE)));
            JsonElement li = get(f, "LinkedId", "linkedId");
            nf.add("linkedId", (li != null && li.isJsonPrimitive())
                    ? new JsonPrimitive(li.getAsInt()) : JsonNull.INSTANCE);
            fields.add(nf);
        }
        return fields;
    }

    private static JsonElement jsonOrNull(String s) {
        return s == null ? JsonNull.INSTANCE : new JsonPrimitive(s);
    }

    private static boolean boolOr(JsonObject o, boolean def, String... names) {
        JsonElement e = get(o, names);
        try {
            return (e != null && e.isJsonPrimitive()) ? e.getAsBoolean() : def;
        } catch (Exception _) {
            return def;
        }
    }

    // ------------------------------------------------------------------
    // internal orchestration
    // ------------------------------------------------------------------

    private void ensureFresh() {
        Instant now = Instant.now(clock);
        if (cachedItems != null && now.isBefore(cacheExpiry)) {
            return;
        }
        try {
            if (userKey == null || now.isAfter(loginExpiry)) {
                login();
            }
            cachedItems = sync();
            cacheExpiry = now.plusSeconds(Math.max(1, props.getCacheTtlSeconds()));
            fehlversucheInFolge = 0;
            letzterRefreshGescheitert = false;
            letzterFehlerWarRateLimit = false;
            letzteFehlermeldung = "";
            log.debug("Vaultwarden-Sync ok: {} Login-Items, {} Org-Keys",
                    cachedItems.size(), orgKeys.size());
        } catch (Exception e) {
            // fail-safe: do not break any boot/consumer; keep the last data
            if (cachedItems == null) {
                cachedItems = List.of();
            }
            letzterRefreshGescheitert = true;
            letzteFehlermeldung = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            cacheExpiry = now.plusSeconds(backoffSekunden(e));
        }
    }

    /** {@code true} when the last login/sync attempt failed (transient disturbance). */
    synchronized boolean istLetzterRefreshGescheitert() {
        return letzterRefreshGescheitert;
    }

    /** {@code true} when the last failure was a recognized rate limit (HTTP 429). */
    synchronized boolean warLetzterFehlerRateLimit() {
        return letzterFehlerWarRateLimit;
    }

    /** Secret-free message of the last failure; empty after a success. */
    synchronized String letzteFehlermeldung() {
        return letzteFehlermeldung;
    }

    /**
     * Waiting time until the next Vaultwarden attempt after a failure (Karte 395) — and the log
     * message that goes with it.
     *
     * <p><b>Why this became necessary:</b> previously a retry followed at most 60 seconds after
     * a failure, and it did so without any limit. With an HTTP 429 ("Too many login
     * requests") that is continuous fire — and self-reinforcing at that: every attempt during
     * the block keeps it alive. On 01.08.2026 a single misconfigured INT instance in a crash
     * loop prevented OTHER applications from starting this way, because Vaultwarden locked
     * everyone out.</p>
     *
     * <p>Hence: exponentially from {@value #BACKOFF_START_SEK}s up to {@value #BACKOFF_MAX_SEK}s,
     * and on a recognized rate limit immediately {@value #BACKOFF_RATE_LIMIT_SEK}s — a rate limit
     * only expires if it is left alone.</p>
     *
     * <p>The message is deliberately at WARN and states the waiting time: a silent backoff looks
     * like a hanging service in the log.</p>
     */
    private int backoffSekunden(Exception e) {
        fehlversucheInFolge++;
        String meldung = e.getMessage() == null ? "" : e.getMessage();
        boolean rateLimit = meldung.contains("429") || meldung.contains("Too many");
        letzterFehlerWarRateLimit = rateLimit;

        int wartezeit;
        if (rateLimit) {
            wartezeit = BACKOFF_RATE_LIMIT_SEK;
            log.warn("Vaultwarden riegelt mit einem Rate-Limit ab ({}). Naechster Versuch erst in {}s "
                            + "— weitere Versuche wuerden die Sperre nur verlaengern. Fehlversuch Nr. {}.",
                    meldung, wartezeit, fehlversucheInFolge);
        } else {
            // 30, 60, 120, 240, ... up to BACKOFF_MAX_SEK
            long exponentiell = (long) BACKOFF_START_SEK << Math.min(fehlversucheInFolge - 1, 20);
            wartezeit = (int) Math.min(exponentiell, BACKOFF_MAX_SEK);
            log.warn("Vaultwarden-Zugriff fehlgeschlagen ({}). Fahre fail-safe fort, "
                            + "naechster Versuch in {}s (Fehlversuch Nr. {}).",
                    meldung, wartezeit, fehlversucheInFolge);
        }
        return wartezeit;
    }

    // ------------------------------------------------------------------
    // Steps 1-6: login (prelogin -> KDF -> token -> userKey)
    // ------------------------------------------------------------------

    private void login() throws Exception {
        String baseUrl = trimUrl(props.getUrl());
        String emailLower = props.getEmail() == null ? "" : props.getEmail().trim().toLowerCase(Locale.ROOT);
        char[] masterPw = props.getMasterPassword() == null ? new char[0] : props.getMasterPassword().toCharArray();
        byte[] masterPwBytes = new String(masterPw).getBytes(StandardCharsets.UTF_8);

        // 1. prelogin -> kdf, kdfIterations
        JsonObject pre = postJson(baseUrl + "/identity/accounts/prelogin",
                "{\"email\":\"" + jsonEscape(emailLower) + "\"}");
        int kdf = intOr(pre, 0, "kdf", "Kdf");
        int iterations = intOr(pre, 600000, "kdfIterations", "KdfIterations");
        if (kdf != 0) {
            throw new IllegalStateException("Nicht unterstuetzter KDF-Typ " + kdf
                    + " (nur PBKDF2/KDF=0; Argon2 waere ein BouncyCastle-Follow-up)");
        }

        // 2. masterKey
        byte[] masterKey = VaultwardenCrypto.pbkdf2Sha256(
                masterPwBytes, emailLower.getBytes(StandardCharsets.UTF_8), iterations, 32);
        // 3. masterPasswordHash = base64(PBKDF2(masterKey, masterPassword, 1))
        byte[] mphBytes = VaultwardenCrypto.pbkdf2Sha256(masterKey, masterPwBytes, 1, 32);
        String masterPasswordHash = Base64.getEncoder().encodeToString(mphBytes);

        // 4. token
        JsonObject tok = requestToken(baseUrl, emailLower, masterPasswordHash);
        String newAccessToken = getStr(tok, "access_token", "accessToken");
        String encUserKey = getStr(tok, "Key", "key");
        long expiresIn = longOr(tok, 3600, "expires_in", "expiresIn");
        if (newAccessToken == null || encUserKey == null) {
            throw new IllegalStateException("Token-Antwort ohne access_token/Key");
        }

        // 5. stretched master key, 6. userKey
        byte[] stretched = VaultwardenCrypto.stretchMasterKey(masterKey);
        byte[] uk = VaultwardenCrypto.decryptSymmetric(EncString.parse(encUserKey), stretched);

        this.userKey = uk;
        this.accessToken = newAccessToken;
        long ttlSec = Math.min((long) Math.max(1, props.getLoginTtlMinutes()) * 60, Math.max(60, expiresIn) - 30);
        this.loginExpiry = Instant.now(clock).plusSeconds(Math.max(30, ttlSec));
        // masterKey/stretched are local variables -> eligible for GC after derivation
        log.info("Vaultwarden-Login ok fuer {} (KDF=PBKDF2, {} Iterationen)", emailLower, iterations);
    }

    private JsonObject requestToken(String baseUrl, String emailLower, String masterPasswordHash) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        boolean useClientCreds = notBlank(props.getClientId()) && notBlank(props.getClientSecret());
        if (useClientCreds) {
            form.put("grant_type", "client_credentials");
            form.put("client_id", props.getClientId());
            form.put("client_secret", props.getClientSecret());
            form.put("scope", "api");
        } else {
            form.put("grant_type", KEY_PASSWORD);
            form.put(KEY_USERNAME, emailLower);
            form.put(KEY_PASSWORD, masterPasswordHash);
            form.put("scope", "api offline_access");
            form.put("client_id", "cli");
        }
        form.put("deviceType", "8");
        form.put("deviceIdentifier", deviceIdentifier);
        form.put("deviceName", deviceName);

        String authEmail = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(emailLower.getBytes(StandardCharsets.UTF_8));

        HttpRequest req = baseRequest(baseUrl + "/identity/connect/token")
                .header(HEADER_CONTENT_TYPE, "application/x-www-form-urlencoded")
                .header("Auth-Email", authEmail)
                .POST(HttpRequest.BodyPublishers.ofString(urlEncode(form)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("token-Endpoint HTTP " + resp.statusCode()
                    + " (" + shortBody(resp.body()) + ")");
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    // ------------------------------------------------------------------
    // Steps 7-11: sync + decryption
    // ------------------------------------------------------------------

    private List<VaultwardenItem> sync() throws Exception {
        String baseUrl = trimUrl(props.getUrl());
        HttpRequest req = baseRequest(baseUrl + "/api/sync?excludeDomains=true")
                .header(HEADER_AUTHORIZATION, BEARER_PREFIX + accessToken)
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("sync-Endpoint HTTP " + resp.statusCode()
                    + " (" + shortBody(resp.body()) + ")");
        }
        JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonObject profile = getObj(root, "Profile", "profile");

        // 8. RSA-PrivateKey, 9. Org-Keys, 10./11. Ciphers
        PrivateKey privateKey = extractPrivateKey(profile);
        loadOrgKeys(profile, privateKey);
        return decryptCiphers(root);
    }

    /** 8. Decrypts the RSA private key from the profile ({@code null} when there is none). */
    private PrivateKey extractPrivateKey(JsonObject profile) {
        String privEnc = profile == null ? null : getStr(profile, "PrivateKey", "privateKey");
        if (privEnc == null) {
            return null;
        }
        byte[] der = VaultwardenCrypto.decryptSymmetric(EncString.parse(privEnc), userKey);
        return VaultwardenCrypto.rsaPrivateKeyFromPkcs8(der);
    }

    /** 9. Decrypts all org keys with the RSA private key into {@link #orgKeys} (fail-safe per org). */
    private void loadOrgKeys(JsonObject profile, PrivateKey privateKey) {
        orgKeys.clear();
        JsonArray orgs = getArr(profile, "Organizations", "organizations");
        if (orgs == null || privateKey == null) {
            return;
        }
        for (JsonElement el : orgs) {
            JsonObject org = el.getAsJsonObject();
            String orgId = getStr(org, "Id", "id");
            String orgKeyStr = getStr(org, "Key", "key");
            if (orgId == null || orgKeyStr == null) {
                continue;
            }
            try {
                byte[] orgKey = VaultwardenCrypto.decryptRsaOaepSha1(EncString.parse(orgKeyStr), privateKey);
                orgKeys.put(orgId, orgKey);
            } catch (Exception e) {
                log.warn("Org-Key fuer Organisation {} nicht entschluesselbar: {}", orgId, e.getMessage());
            }
        }
    }

    /** 10./11. Decrypts all login ciphers (fail-safe per cipher). */
    private List<VaultwardenItem> decryptCiphers(JsonObject root) {
        List<VaultwardenItem> items = new ArrayList<>();
        JsonArray ciphers = getArr(root, "Ciphers", "ciphers");
        if (ciphers != null) {
            for (JsonElement el : ciphers) {
                try {
                    VaultwardenItem item = decryptCipher(el.getAsJsonObject());
                    if (item != null) {
                        items.add(item);
                    }
                } catch (Exception e) {
                    log.debug("Cipher uebersprungen (nicht entschluesselbar): {}", e.getMessage());
                }
            }
        }
        return List.copyOf(items);
    }

    /** Decrypts a single login cipher (type==1). Other types: {@code null}. */
    private VaultwardenItem decryptCipher(JsonObject cipher) {
        int type = intOr(cipher, 0, "Type", "type");
        if (type != 1) {
            return null; // login items only
        }
        String orgId = getStr(cipher, ORGANIZATION_ID_PASCAL, ORGANIZATION_ID);
        byte[] baseKey = (orgId != null) ? orgKeys.get(orgId) : userKey;
        if (baseKey == null) {
            return null; // org key missing -> cannot be decrypted
        }
        // per-cipher key (modern Bitwarden ciphers)
        byte[] itemKey = baseKey;
        String cipherKeyStr = getStr(cipher, "Key", "key");
        if (cipherKeyStr != null) {
            itemKey = VaultwardenCrypto.decryptSymmetric(EncString.parse(cipherKeyStr), baseKey);
        }

        String name = decStr(getStr(cipher, "Name", "name"), itemKey);
        String username = null;
        String password = null;
        JsonObject login = getObj(cipher, "Login", KEY_LOGIN);
        if (login != null) {
            username = decStr(getStr(login, "Username", KEY_USERNAME), itemKey);
            password = decStr(getStr(login, "Password", KEY_PASSWORD), itemKey);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        JsonArray fieldArr = getArr(cipher, "Fields", KEY_FIELDS);
        if (fieldArr != null) {
            for (JsonElement fe : fieldArr) {
                JsonObject f = fe.getAsJsonObject();
                String fName = decStr(getStr(f, "Name", "name"), itemKey);
                String fValue = decStr(getStr(f, "Value", KEY_VALUE), itemKey);
                if (fName != null) {
                    fields.put(fName, fValue);
                }
            }
        }
        String id = getStr(cipher, "Id", "id");
        return new VaultwardenItem(id, name, username, password, fields);
    }

    private String decStr(String encString, byte[] key) {
        if (encString == null || encString.isEmpty()) {
            return null;
        }
        byte[] plain = VaultwardenCrypto.decryptSymmetric(EncString.parse(encString), key);
        return new String(plain, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    private HttpRequest.Builder baseRequest(String url) {
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(5, props.getHttpTimeoutSeconds())))
                .header("User-Agent", props.getUserAgent())
                .header("Accept", MEDIA_TYPE_JSON);
    }

    private JsonObject postJson(String url, String body) throws IOException, InterruptedException {
        HttpRequest req = baseRequest(url)
                .header(HEADER_CONTENT_TYPE, MEDIA_TYPE_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + " von " + url
                    + " (" + shortBody(resp.body()) + ")");
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    // ------------------------------------------------------------------
    // JSON / encoding helpers (tolerant of Pascal/camelCase)
    // ------------------------------------------------------------------

    private static JsonElement get(JsonObject o, String... names) {
        if (o == null) {
            return null;
        }
        for (String n : names) {
            if (o.has(n) && !o.get(n).isJsonNull()) {
                return o.get(n);
            }
        }
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            for (String n : names) {
                if (e.getKey().equalsIgnoreCase(n) && !e.getValue().isJsonNull()) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    private static String getStr(JsonObject o, String... names) {
        JsonElement e = get(o, names);
        return (e != null && e.isJsonPrimitive()) ? e.getAsString() : null;
    }

    private static JsonObject getObj(JsonObject o, String... names) {
        JsonElement e = get(o, names);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    private static JsonArray getArr(JsonObject o, String... names) {
        JsonElement e = get(o, names);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    private static int intOr(JsonObject o, int def, String... names) {
        JsonElement e = get(o, names);
        try {
            return (e != null && e.isJsonPrimitive()) ? e.getAsInt() : def;
        } catch (Exception _) {
            return def;
        }
    }

    private static long longOr(JsonObject o, long def, String... names) {
        JsonElement e = get(o, names);
        try {
            return (e != null && e.isJsonPrimitive()) ? e.getAsLong() : def;
        } catch (Exception _) {
            return def;
        }
    }

    private static String urlEncode(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimUrl(String url) {
        String u = (url == null || url.isBlank()) ? "https://vault.plaintext.ch" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /** Short, secret-free excerpt for error messages. */
    private static String shortBody(String body) {
        if (body == null) {
            return "";
        }
        String b = body.strip();
        return b.length() > 160 ? b.substring(0, 160) + "..." : b;
    }
}
