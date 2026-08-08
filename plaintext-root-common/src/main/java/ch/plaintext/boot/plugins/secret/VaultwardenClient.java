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
 * HTTP- und Krypto-Orchestrierung des Bitwarden/Vaultwarden-Flows.
 *
 * <p>Haelt userKey, orgKeys und die entschluesselten Login-Items im Speicher
 * (mit TTL); loggt bei Ablauf neu ein und synchronisiert neu. Thread-safe ueber
 * ein einziges {@code synchronized ensureFresh()}. ALLE Fehler sind fail-safe:
 * es wird geloggt (nie Secret-Werte) und mit Leerdaten weitergemacht — der
 * App-Boot darf niemals daran scheitern.</p>
 *
 * <p>Nur JDK/JCA + Gson — kein BouncyCastle (KDF=0 = PBKDF2).</p>
 */
@Slf4j
class VaultwardenClient {

    // Wiederverwendete JSON-/Form-Keys und HTTP-Header (Pascal-/camelCase-tolerant).
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
     * STABILE Geraete-UUID: deterministisch aus Email + App-Name abgeleitet (bzw. der explizit
     * konfigurierte Wert). Bleibt ueber Neustarts/Redeploys gleich, sodass Vaultwarden den Login
     * nicht als „neues Geraet" wertet und keine „New Device Logged In"-Mail schickt.
     */
    private final String deviceIdentifier;
    /** Anzeigename des Geraets ({@code deviceName}), inkl. App-Name zur Unterscheidung im Vault. */
    private final String deviceName;

    // --- gecachter, entschluesselter Zustand (guarded by this) ---
    private byte[] userKey;                       // 64B (enc32||mac32)
    private String accessToken;
    private final Map<String, byte[]> orgKeys = new HashMap<>();
    private List<VaultwardenItem> cachedItems = null;
    private Instant loginExpiry = Instant.EPOCH;
    private Instant cacheExpiry = Instant.EPOCH;

    /**
     * Zahl der aufeinanderfolgenden fehlgeschlagenen Vaultwarden-Zugriffe — steuert den Backoff
     * (Karte 395). Wird bei jedem Erfolg auf 0 zurueckgesetzt.
     */
    private int fehlversucheInFolge = 0;

    /** Backoff nach dem ersten Fehlschlag; verdoppelt sich je weiterem bis {@link #BACKOFF_MAX_SEK}. */
    private static final int BACKOFF_START_SEK = 30;
    /** Obergrenze des Backoffs — 15 Minuten. Danach wird weiter periodisch, aber selten versucht. */
    private static final int BACKOFF_MAX_SEK = 900;
    /**
     * Backoff nach einem HTTP 429. Deutlich laenger als der normale Einstieg: Ein Rate-Limit laeuft
     * nur ab, wenn man es NICHT weiter fuettert — jeder Versuch waehrend der Sperre verlaengert sie.
     */
    private static final int BACKOFF_RATE_LIMIT_SEK = 300;

    /**
     * Zeitquelle fuer Cache-Ablauf und Backoff.
     *
     * <p>Karte 608: Vorher stand hier ueberall {@code Instant.now()}. Die Tests mussten deshalb
     * mit {@code Thread.sleep(1300)} echte Zeit verstreichen lassen, um einen Backoff zu
     * ueberspringen — zwei Sekunden Wartezeit je Lauf, und Sonar meldet {@code Thread.sleep} in
     * Tests zu Recht (java:S2925): Ein Test, der auf die Uhr wartet, ist auf einem langsamen
     * Runner flaky und auf einem schnellen langsam.
     *
     * <p>Im Betrieb ist es unveraendert {@link Clock#systemUTC()}; nur der Test setzt eine
     * eigene Uhr ein und stellt sie vor.</p>
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
        // Explizit konfiguriert? sonst stabil aus Email+App ableiten (nicht random → keine Device-Mails).
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
     * Liefert die aktuell entschluesselten Login-Items (fail-safe: nie {@code null},
     * bei Fehlern leer). Aktualisiert Login/Sync bei abgelaufenem Cache.
     */
    synchronized List<VaultwardenItem> getItems() {
        ensureFresh();
        return cachedItems != null ? cachedItems : List.of();
    }

    /** Invalidiert den Cache (z.B. nach einer Rotation). */
    synchronized void invalidate() {
        cachedItems = null;
        cacheExpiry = Instant.EPOCH;
    }

    // ------------------------------------------------------------------
    // Rotation (Schreibrichtung) — Bitwarden-kompatibler PUT auf den ciphers-Endpunkt
    // ------------------------------------------------------------------

    /**
     * Setzt das Passwort des benannten Login-Items neu (Rotation) und laesst ALLE
     * uebrigen Felder unveraendert.
     *
     * <p>Ablauf:</p>
     * <ol>
     *   <li>Login + Sync sicherstellen; Item ueber den entschluesselten Namen finden
     *       (exakt-dann-enthaelt, wie im Read-Flow).</li>
     *   <li>Aktuellen Cipher-Stand via {@code GET /api/ciphers/{id}} holen und den
     *       passenden Schluessel (per-Cipher-Key ueber org/user-Key) ableiten.</li>
     *   <li>Nur {@code login.password} durch {@link VaultwardenCrypto#encryptSymmetric}
     *       ersetzen; alle anderen EncString-Felder (name, username, uris, notes,
     *       fields, totp) unveraendert durchreichen (sie bleiben verschluesselt —
     *       KEIN Re-Encrypt, sonst Datenverlust).</li>
     *   <li>{@code PUT /api/ciphers/{id}} mit dem CipherRequestModel; bei 2xx Cache
     *       invalidieren und {@code true} liefern.</li>
     * </ol>
     *
     * <p>Fail-safe: bei jedem Fehler {@code false} + {@code log.warn} (nie Secrets),
     * es wird keine Exception nach aussen gereicht.</p>
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

            // aktuellen Cipher-Stand frisch holen (nicht aus dem TTL-Cache)
            JsonObject cipher = getCipher(baseUrl, match.id());
            if (cipher == null) {
                log.warn("Rotation: Cipher {} nicht abrufbar", match.id());
                return false;
            }

            // passenden Schluessel ableiten (identisch zum Read-Flow)
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

            // NUR das Passwort neu verschluesseln; alles andere durchreichen
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

    /** Findet ein gecachtes Login-Item ueber den entschluesselten Namen (exakt, dann enthaelt). */
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

    /** Holt den aktuellen Roh-Cipher via {@code GET /api/ciphers/{id}}. */
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

    /** Sendet das aktualisierte CipherRequestModel via {@code PUT /api/ciphers/{id}}. */
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
     * Baut aus dem Roh-Cipher das Bitwarden-{@code CipherRequestModel} (camelCase) und
     * ersetzt dabei ausschliesslich {@code login.password}. Alle anderen EncString-Felder
     * werden unveraendert (verschluesselt) uebernommen — kein Feld wird neu verschluesselt.
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
        // per-Cipher-Key unveraendert durchreichen (wird org/user-Key-verschluesselt gehalten)
        String cipherKey = getStr(cipher, "Key", "key");
        if (cipherKey != null) {
            req.addProperty("key", cipherKey);
        }
        // optimistic concurrency: aktuellen Stand mitgeben
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

    /** Baut das {@code login}-Objekt: nur {@code password} ersetzen, Rest verschluesselt durchreichen. */
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

    /** Uebernimmt die {@code uris}-Liste (uri + match) unveraendert. */
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

    /** Uebernimmt die {@code fields}-Liste (name/value) unveraendert; {@code null} falls keine da. */
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
    // interne Orchestrierung
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
            log.debug("Vaultwarden-Sync ok: {} Login-Items, {} Org-Keys",
                    cachedItems.size(), orgKeys.size());
        } catch (Exception e) {
            // fail-safe: keinen Boot/Consumer brechen; letzte Daten behalten
            if (cachedItems == null) {
                cachedItems = List.of();
            }
            cacheExpiry = now.plusSeconds(backoffSekunden(e));
        }
    }

    /**
     * Wartezeit bis zum naechsten Vaultwarden-Versuch nach einem Fehlschlag (Karte 395) — und die
     * Log-Meldung dazu.
     *
     * <p><b>Warum das noetig wurde:</b> Vorher wurde nach einem Fehlschlag spaetestens nach 60
     * Sekunden erneut versucht, und zwar unbegrenzt oft. Bei einem HTTP 429 („Too many login
     * requests") ist das Dauerfeuer — und zwar selbstverstaerkend: Jeder Versuch waehrend der
     * Sperre haelt sie am Leben. Am 01.08.2026 hat eine einzige fehlkonfigurierte INT-Instanz im
     * Crashloop auf diesem Weg den Start ANDERER Anwendungen verhindert, weil Vaultwarden fuer
     * alle abriegelte.</p>
     *
     * <p>Deshalb: exponentiell ab {@value #BACKOFF_START_SEK}s bis {@value #BACKOFF_MAX_SEK}s, und
     * bei einem erkannten Rate-Limit sofort {@value #BACKOFF_RATE_LIMIT_SEK}s — ein Rate-Limit
     * laeuft nur ab, wenn man es in Ruhe laesst.</p>
     *
     * <p>Die Meldung steht bewusst auf WARN und nennt die Wartezeit: Ein stiller Backoff sieht im
     * Log aus wie ein haengender Dienst.</p>
     */
    private int backoffSekunden(Exception e) {
        fehlversucheInFolge++;
        String meldung = e.getMessage() == null ? "" : e.getMessage();
        boolean rateLimit = meldung.contains("429") || meldung.contains("Too many");

        int wartezeit;
        if (rateLimit) {
            wartezeit = BACKOFF_RATE_LIMIT_SEK;
            log.warn("Vaultwarden riegelt mit einem Rate-Limit ab ({}). Naechster Versuch erst in {}s "
                            + "— weitere Versuche wuerden die Sperre nur verlaengern. Fehlversuch Nr. {}.",
                    meldung, wartezeit, fehlversucheInFolge);
        } else {
            // 30, 60, 120, 240, ... bis BACKOFF_MAX_SEK
            long exponentiell = (long) BACKOFF_START_SEK << Math.min(fehlversucheInFolge - 1, 20);
            wartezeit = (int) Math.min(exponentiell, BACKOFF_MAX_SEK);
            log.warn("Vaultwarden-Zugriff fehlgeschlagen ({}). Fahre fail-safe fort, "
                            + "naechster Versuch in {}s (Fehlversuch Nr. {}).",
                    meldung, wartezeit, fehlversucheInFolge);
        }
        return wartezeit;
    }

    // ------------------------------------------------------------------
    // Schritt 1-6: Login (prelogin -> KDF -> token -> userKey)
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
        // masterKey/stretched sind lokale Variablen -> GC-faehig nach Ableitung
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
    // Schritt 7-11: Sync + Entschluesselung
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

    /** 8. Entschluesselt den RSA-PrivateKey aus dem Profil ({@code null}, wenn keiner vorhanden). */
    private PrivateKey extractPrivateKey(JsonObject profile) {
        String privEnc = profile == null ? null : getStr(profile, "PrivateKey", "privateKey");
        if (privEnc == null) {
            return null;
        }
        byte[] der = VaultwardenCrypto.decryptSymmetric(EncString.parse(privEnc), userKey);
        return VaultwardenCrypto.rsaPrivateKeyFromPkcs8(der);
    }

    /** 9. Entschluesselt alle Org-Keys per RSA-PrivateKey in {@link #orgKeys} (fail-safe je Org). */
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

    /** 10./11. Entschluesselt alle Login-Ciphers (fail-safe je Cipher). */
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

    /** Entschluesselt ein einzelnes Login-Cipher (type==1). Andere Typen: {@code null}. */
    private VaultwardenItem decryptCipher(JsonObject cipher) {
        int type = intOr(cipher, 0, "Type", "type");
        if (type != 1) {
            return null; // nur Login-Items
        }
        String orgId = getStr(cipher, ORGANIZATION_ID_PASCAL, ORGANIZATION_ID);
        byte[] baseKey = (orgId != null) ? orgKeys.get(orgId) : userKey;
        if (baseKey == null) {
            return null; // Org-Key fehlt -> nicht entschluesselbar
        }
        // pro-Cipher-Key (moderne Bitwarden-Ciphers)
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
    // HTTP-Helfer
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
    // JSON- / Encoding-Helfer (tolerant gegenueber Pascal-/camelCase)
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

    /** Kurzer, secret-freier Ausschnitt fuer Fehlermeldungen. */
    private static String shortBody(String body) {
        if (body == null) {
            return "";
        }
        String b = body.strip();
        return b.length() > 160 ? b.substring(0, 160) + "..." : b;
    }
}
