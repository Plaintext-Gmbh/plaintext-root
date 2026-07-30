/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import ch.plaintext.boot.plugins.security.PlaintextSecurityHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HashiCorp-Vault-Backend (KV v2). Konfiguration (URL/Token/Mount) liegt AES-verschlüsselt in
 * {@code secret_backend_config} und wird über {@link SecretCrypto} entschlüsselt gelesen — das Token
 * wird nie im Klartext gehalten/geloggt. Werte werden geschrieben (one-way); die Notiz landet als
 * {@code note}-Feld neben dem Wert, {@link #comment} liest NUR die Notiz (nie den Wert).
 *
 * <p>Config-JSON (im Settings-Feld einzugeben): {@code {"url":"https://vault:8200","token":"hvs...",
 * "mount":"secret"}} — {@code mount} optional (Default {@code secret}).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HashiCorpVaultBackend implements SecretBackend {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SecretBackendConfigRepository configRepo;
    private final SecretCrypto crypto;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Override
    public SecretBackendType type() {
        return SecretBackendType.HASHICORP;
    }

    @Override
    public boolean isAvailable() {
        return config() != null;
    }

    @Override
    public SecretHealth health() {
        Cfg c = config();
        if (c == null) {
            return SecretHealth.down("Config fehlt/unvollständig — url + token (+ optional mount) als JSON "
                    + "eingeben, z.B. {\"url\":\"https://vault:8200\",\"token\":\"hvs...\",\"mount\":\"secret\"}.");
        }
        try {
            HttpResponse<String> r = http.send(get(c, "/v1/auth/token/lookup-self"),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                return SecretHealth.up("HashiCorp-Vault erreichbar, Token gültig (mount=" + c.mount + ").");
            }
            if (r.statusCode() == 403) {
                return SecretHealth.down("Token ungültig oder abgelaufen (HTTP 403) — Token in den Einstellungen erneuern.");
            }
            return SecretHealth.down("Vault antwortet mit HTTP " + r.statusCode() + " — URL/Mount prüfen.");
        } catch (Exception e) {
            return SecretHealth.down("Vault nicht erreichbar (" + c.url + "): " + e.getMessage());
        }
    }

    @Override
    public String readValue(String name) {
        Cfg c = config();
        if (c == null) {
            return null;
        }
        try {
            HttpResponse<String> r = http.send(get(c, "/v1/" + c.mount + "/data/" + enc(name)),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                JsonNode val = JSON.readTree(r.body()).path("data").path("data").path("value");
                return val.isMissingNode() || val.isNull() ? null : val.asText();
            }
        } catch (Exception e) {
            log.debug("HashiCorp readValue({}) fehlgeschlagen: {}", name, e.getMessage());
        }
        return null;
    }

    @Override
    public String comment(String name) {
        Cfg c = config();
        if (c == null) {
            return null;
        }
        try {
            HttpResponse<String> r = http.send(get(c, "/v1/" + c.mount + "/data/" + enc(name)),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                JsonNode note = JSON.readTree(r.body()).path("data").path("data").path("note");
                return note.isMissingNode() || note.isNull() ? null : note.asText();
            }
        } catch (Exception e) {
            log.debug("HashiCorp comment({}) fehlgeschlagen: {}", name, e.getMessage());
        }
        return null;
    }

    @Override
    public void set(String name, String value, String note) {
        Cfg c = config();
        if (c == null) {
            throw new IllegalStateException("HashiCorp-Vault-Backend nicht konfiguriert (Settings)");
        }
        try {
            ObjectNode data = JSON.createObjectNode();
            if (value != null) {
                data.put("value", value);
            }
            if (note != null) {
                data.put("note", note);
            }
            ObjectNode body = JSON.createObjectNode();
            body.set("data", data);
            HttpRequest req = HttpRequest.newBuilder(URI.create(c.url + "/v1/" + c.mount + "/data/" + enc(name)))
                    .timeout(Duration.ofSeconds(20))
                    .header("X-Vault-Token", c.token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new IllegalStateException("HashiCorp-Vault-Write fehlgeschlagen (HTTP " + r.statusCode() + ")");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("HashiCorp-Vault nicht erreichbar: " + e.getMessage());
        }
    }

    // ── intern ───────────────────────────────────────────────

    private HttpRequest get(Cfg c, String path) {
        return HttpRequest.newBuilder(URI.create(c.url + path))
                .timeout(Duration.ofSeconds(20))
                .header("X-Vault-Token", c.token)
                .GET().build();
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Aktive HASHICORP-Config des Mandanten (entschlüsselt), oder null. */
    private Cfg config() {
        String mandat = PlaintextSecurityHolder.getMandat();
        return configRepo.findFirstByMandatAndAktivAndDeleted(mandat, true, false)
                .filter(cfg -> cfg.getBackendType() == SecretBackendType.HASHICORP && cfg.getConfigEncrypted() != null)
                .map(cfg -> {
                    try {
                        JsonNode j = JSON.readTree(crypto.decrypt(cfg.getConfigEncrypted()));
                        String url = j.path("url").asText(null);
                        String token = j.path("token").asText(null);
                        String mount = j.path("mount").asText("secret");
                        if (url == null || token == null || url.isBlank() || token.isBlank()) {
                            return null;
                        }
                        return new Cfg(url.replaceAll("/+$", ""), token, mount);
                    } catch (Exception e) {
                        log.warn("HashiCorp-Config nicht lesbar: {}", e.getMessage());
                        return null;
                    }
                }).orElse(null);
    }

    private record Cfg(String url, String token, String mount) { }
}
