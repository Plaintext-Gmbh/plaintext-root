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
 * Die eigentliche Zugriffspruefung hinter {@code /deeplink} (Karte 345) — bewusst ohne Servlet-API,
 * damit sie ohne Web-Kontext testbar ist.
 *
 * <p><b>Reihenfolge der Pruefungen (jede einzelne ist fail-closed):</b>
 * <ol>
 *   <li><b>Zeichenmuster</b> — siehe {@link DeepLinkFormat}. Alles ausserhalb wird abgewiesen,
 *       bevor es in eine Redirect-URL gelangen kann.</li>
 *   <li><b>Registrierter Typ</b> — die Ziel-View kommt aus der Registry, nie aus der URL. Ein
 *       unbekannter Typ wird abgelehnt (kein „einfach durchreichen").</li>
 *   <li><b>Mandat-Zugriff</b> — {@code getAllowedMandate()}, dieselbe Quelle wie die
 *       Topbar-Mandant-Auswahl. Steht das Ziel-Mandat nicht drin, wird <em>nicht</em> gewechselt,
 *       auch nicht kurz zum Anzeigen.</li>
 *   <li><b>Datensatz-Zugriff</b> — {@link DeepLinkTarget#isAccessible(String, String)} des
 *       zustaendigen Moduls. Eine geratene fremde Id scheitert hier serverseitig, unabhaengig
 *       davon, was das Menue anzeigt.</li>
 * </ol>
 *
 * <p>Der Mandat-Wechsel passiert <em>vor</em> der Datensatz-Pruefung, weil die Module ihre Daten
 * ueber den aktiven Mandanten filtern (Hibernate-Filter/Session-Kontext) und {@code isAccessible}
 * sonst systematisch {@code false} liefern wuerde. Das ist unbedenklich: gewechselt wird nur in
 * einen Mandanten, den der Benutzer ohnehin per Topbar waehlen duerfte. Faellt die Pruefung
 * negativ aus, wird der vorherige Mandat wiederhergestellt, damit ein toter Link den Benutzer
 * nicht stillschweigend umkontextet.
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
            // Fail-closed: ein Fehler in der Modul-Pruefung ist ein „nein", kein „vielleicht".
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
