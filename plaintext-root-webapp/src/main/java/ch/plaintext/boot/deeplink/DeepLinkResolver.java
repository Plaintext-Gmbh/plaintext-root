/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

import ch.plaintext.PlaintextSecurity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The actual access check behind {@code /deeplink} (card 345) — deliberately without the servlet
 * API, so that it is testable without a web context.
 *
 * <p><b>Order of the checks (each one of them fail-closed):</b>
 * <ol>
 *   <li><b>Character pattern</b> — see {@link DeepLinkFormat}. Everything outside it is rejected
 *       before it can reach a redirect URL.</li>
 *   <li><b>Registered type</b> — the target view comes from the registry, never from the URL. An
 *       unknown type is rejected (no "just pass it through").</li>
 *   <li><b>Tenant access</b> — {@code getAllowedMandate()}, the same source as the
 *       tenant selector in the topbar. If the target tenant is not in it, there is <em>no</em> switch,
 *       not even briefly for display purposes.</li>
 *   <li><b>Record access</b> — {@link DeepLinkTarget#isAccessible(String, String)} of the
 *       responsible module. A guessed foreign id fails here on the server side, independently
 *       of what the menu shows.</li>
 * </ol>
 *
 * <p>The tenant switch happens <em>before</em> the record check, because the modules filter their
 * data by the active tenant (Hibernate filter / session context) and {@code isAccessible}
 * would otherwise systematically return {@code false}. That is harmless: the switch only ever goes
 * into a tenant that the user would be allowed to select via the topbar anyway. If the check turns
 * out negative, the previous tenant is restored, so that a dead link does not silently move the
 * user into another context.
 */
@Service
@Slf4j
public class DeepLinkResolver {

    private final DeepLinkService deepLinkService;
    private final PlaintextSecurity plaintextSecurity;

    public DeepLinkResolver(DeepLinkService deepLinkService, PlaintextSecurity plaintextSecurity) {
        this.deepLinkService = deepLinkService;
        this.plaintextSecurity = plaintextSecurity;
    }

    public DeepLinkResolution resolve(String type, String mandat, String id) {
        String t = type == null ? null : type.trim().toLowerCase(Locale.ROOT);
        String m = mandat == null ? null : mandat.trim().toLowerCase(Locale.ROOT);
        String i = id == null ? null : id.trim();

        if (!DeepLinkFormat.istGueltigerType(t)
                || !DeepLinkFormat.istGueltigesMandat(m)
                || !DeepLinkFormat.istGueltigeId(i)) {
            log.warn("Deep-Link mit ungueltigen Parametern abgelehnt (Benutzer {})", plaintextSecurity.getUser());
            return DeepLinkResolution.abgelehnt(DeepLinkResolution.Ergebnis.UNGUELTIGE_PARAMETER);
        }

        Optional<DeepLinkTarget> ziel = deepLinkService.findTarget(t);
        if (ziel.isEmpty()) {
            log.warn("Deep-Link auf nicht registrierten Typ '{}' abgelehnt (Benutzer {})",
                    t, plaintextSecurity.getUser());
            return DeepLinkResolution.abgelehnt(DeepLinkResolution.Ergebnis.UNBEKANNTER_TYP);
        }
        DeepLinkTarget target = ziel.get();

        Set<String> erlaubteMandate = plaintextSecurity.getAllowedMandate();
        if (erlaubteMandate == null || !erlaubteMandate.contains(m)) {
            log.warn("Deep-Link auf fremdes Mandat '{}' abgelehnt (Benutzer {}, Typ {})",
                    m, plaintextSecurity.getUser(), t);
            return DeepLinkResolution.abgelehnt(DeepLinkResolution.Ergebnis.MANDAT_VERWEIGERT);
        }

        String vorherigesMandat = plaintextSecurity.getMandat();
        boolean gewechselt = false;
        if (!m.equalsIgnoreCase(vorherigesMandat)) {
            plaintextSecurity.switchActiveMandat(m);
            gewechselt = true;
        }

        boolean erlaubt;
        try {
            erlaubt = target.isAccessible(m, i);
        } catch (Exception e) {
            // Fail-closed: an error in the module check is a "no", not a "maybe".
            log.warn("Deep-Link: Zugriffspruefung des Ziels '{}' schlug fehl -> abgelehnt: {}", t, e.getMessage());
            erlaubt = false;
        }

        if (!erlaubt) {
            if (gewechselt) {
                plaintextSecurity.switchActiveMandat(vorherigesMandat);
            }
            log.warn("Deep-Link auf nicht zugaenglichen Datensatz abgelehnt (Benutzer {}, Typ {}, Mandat {})",
                    plaintextSecurity.getUser(), t, m);
            return DeepLinkResolution.abgelehnt(DeepLinkResolution.Ergebnis.DATENSATZ_VERWEIGERT);
        }

        String param = target.getParamName() == null || target.getParamName().isBlank()
                ? "id" : target.getParamName();
        return DeepLinkResolution.ok("/" + target.getView() + "?" + param + "=" + i);
    }
}
