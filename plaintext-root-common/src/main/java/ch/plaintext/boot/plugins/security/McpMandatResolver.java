/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.PlaintextSecurity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Zentrale Auflösung des Mandanten für MCP-Tools (und andere token-basierte Zugriffe).
 *
 * <p>Regel (nutzt {@link PlaintextSecurity#getAllowedMandate()} — dort steckt bereits „ROOT darf
 * alle Mandate, sonst Heimat-Mandant + zugeordnete Zusatz-Mandate"):</p>
 * <ul>
 *   <li>Kein Mandat angegeben → aktueller Mandant des Tokens ({@link PlaintextSecurity#getMandat()}).</li>
 *   <li>Angegebener Mandat ist erlaubt → dieser Mandant.</li>
 *   <li>Sonst → {@link SecurityException} (cross-tenant verweigert).</li>
 * </ul>
 *
 * <p>Voraussetzung: der Security-Context enthält die echten Rollen des Token-Users (der MCP-
 * BearerToken-Filter lädt sie), damit {@code getAllowedMandate()} für ROOT alle Mandate liefert.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class McpMandatResolver {

    private final PlaintextSecurity security;

    /**
     * Löst den effektiven Mandanten auf.
     *
     * @param requestedMandat optionaler Mandat-Parameter (null/leer = aktueller Mandant)
     * @return der zu verwendende Mandant
     * @throws SecurityException wenn der angefragte Mandant nicht erlaubt ist
     */
    public String resolve(String requestedMandat) {
        if (requestedMandat == null || requestedMandat.isBlank()) {
            return security.getMandat();
        }
        String req = requestedMandat.trim();
        Set<String> allowed = security.getAllowedMandate(); // kleingeschrieben
        if (allowed.contains(req.toLowerCase())) {
            return req;
        }
        log.warn("MCP: Mandat '{}' nicht erlaubt (User {} / erlaubt: {})", req, security.getUser(), allowed);
        throw new SecurityException("Mandant '" + req + "' ist für diesen Token nicht erlaubt");
    }

    /** @return true, wenn der aktuelle Token den angefragten Mandanten nutzen darf. */
    public boolean isAllowed(String requestedMandat) {
        if (requestedMandat == null || requestedMandat.isBlank()) {
            return true;
        }
        return security.getAllowedMandate().contains(requestedMandat.trim().toLowerCase());
    }
}
