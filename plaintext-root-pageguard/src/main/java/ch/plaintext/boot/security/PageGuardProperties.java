/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Konfiguration des Seiten-Zugriffsschutzes ({@link PageAccessGuardService} /
 * {@link PageAccessGuardFilter}).
 *
 * <p><b>Warum der Default {@link PageGuardMode#REPORT} ist:</b> das Framework wird von
 * mehreren Apps konsumiert, die eigene Views und eigene {@code @MenuAnnotation}s mitbringen.
 * Ein sofortiges {@link PageGuardMode#STRICT} wuerde dort jede View ohne Menueeintrag
 * aussperren. {@code REPORT} setzt alle uebrigen Verschaerfungen (kanonischer Link-Vergleich,
 * {@code catch} -> verweigern, Allowlist, Aliase) durch, laesst aber Views ohne Zuordnung mit
 * einer WARN-Meldung passieren — so bekommt jede App erst ihre Lueckenliste ins Log und kann
 * dann gezielt auf {@code STRICT} umstellen. Die root-App selbst laeuft in {@code STRICT}
 * (gesetzt in ihrer {@code application.yml}).
 *
 * <p><b>Praefix.</b> Diese Klasse war bis 1.491.0 die innere Klasse
 * {@code PlaintextSecurityProperties.PageGuardProperties} und band unter
 * {@code plaintext.security} an das Feld {@code pageGuard}. Der wirksame Praefix
 * {@code plaintext.security.page-guard} ist beim Herausloesen unveraendert geblieben —
 * bestehende {@code application.yml} und Umgebungsvariablen wirken weiter. Nur wer die
 * Java-Klasse direkt referenziert hat, muss den Import anpassen.
 *
 * @author plaintext.ch
 * @since 1.492.0
 */
@ConfigurationProperties(prefix = "plaintext.security.page-guard")
@Data
public class PageGuardProperties {

    /**
     * Not-Aus. Bei {@code false} prueft weder Filter noch {@code preRenderView}-Guard
     * (Spring-Security-Regeln in {@code PlaintextSecurityConfig} bleiben davon unberuehrt).
     * Nur fuer den Fall gedacht, dass der Guard in PROD legitime Seiten sperrt und kein
     * Rollback moeglich ist.
     */
    private boolean enabled = true;

    /**
     * Verhalten bei Views ohne Menuezuordnung und Eltern-Rollen-Vererbung.
     * Siehe {@link PageGuardMode}.
     */
    private PageGuardMode mode = PageGuardMode.REPORT;

    /**
     * Zusaetzlich immer erreichbare Views (ergaenzend zu den Framework-Defaults in
     * {@link PageAccessGuardService}). Endung und fuehrender Slash sind egal
     * ({@code /myview.xhtml} == {@code myview.html} == {@code myview}). Ein Eintrag, der auf
     * {@code /**} endet, wirkt als Praefix ({@code nosec/**}).
     */
    private List<String> allowlist = new ArrayList<>();

    /**
     * View-Aliase: „bewache diese View wie diesen Menuelink". Schluessel ist die View, Wert
     * der Menue-Link, dessen Rollen/Mandanten-Sichtbarkeit gelten sollen. Fuer Detailseiten
     * ohne eigenen Menueeintrag, z.B.
     * {@code rechnungdetail.xhtml: rechnungen.html}. Beide Seiten werden kanonisiert, die
     * Endung ist also egal.
     */
    private Map<String, String> aliases = new LinkedHashMap<>();

    /**
     * Ob {@link PageAccessGuardStartupReport} beim Start die ausgelieferten Facelets scannt und die
     * ohne Zugriffsregel meldet. Der Scan liest jede View einmal ein; bei sehr vielen Views oder in
     * Tests kann man ihn hiermit abschalten.
     */
    private boolean startupReport = true;
}
