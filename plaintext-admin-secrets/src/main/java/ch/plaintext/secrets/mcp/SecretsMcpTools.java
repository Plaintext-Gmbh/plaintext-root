/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets.mcp;

import ch.plaintext.secrets.SecretBackendType;
import ch.plaintext.secrets.SecretService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * MCP-Tool zum Setzen von Secrets. Bewusst nur <b>set</b> (one-way — Werte werden nie ausgelesen).
 *
 * <p>{@link ConditionalOnClass} auf die MCP-Annotation: das Bean lädt NUR in Apps, die selbst einen
 * spring-ai-MCP-Server haben (guild/iot/…). Apps ohne MCP bleiben unberührt (die optionale Dependency
 * ist nicht transitiv, und die Condition verhindert das Laden dieser Klasse).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnClass(name = "org.springaicommunity.mcp.annotation.McpTool")
public class SecretsMcpTools {

    private final SecretService secretService;

    @McpTool(name = "set_secret",
            description = "Set (create or overwrite) a secret — ONE-WAY, the value is never read back. "
            + "backend = VAULTWARDEN (write to the shared vault), LOCAL_DB (AES-encrypted in this app's DB) "
            + "or HASHICORP (configured HashiCorp Vault). note = optional free-text comment. "
            + "The entry's creation timestamp is recorded automatically.")
    public String setSecret(
            @McpToolParam(description = "Secret name / key") String name,
            @McpToolParam(description = "Backend: VAULTWARDEN, LOCAL_DB or HASHICORP") String backend,
            @McpToolParam(description = "The secret value to store (write-only)") String value,
            @McpToolParam(description = "Optional free-text note/comment") String note) {
        SecretBackendType type;
        try {
            type = SecretBackendType.valueOf(backend.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return "FEHLER: ungueltiges backend '" + backend + "' — erlaubt: VAULTWARDEN, LOCAL_DB, HASHICORP";
        }
        try {
            secretService.set(name, type, value, note);
            log.info("MCP: set_secret '{}' ({})", name, type);
            return "OK: Secret '" + name + "' gesetzt (" + type + ").";
        } catch (RuntimeException e) {
            return "FEHLER: " + e.getMessage();
        }
    }
}
