/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

/**
 * SPI fuer Deep-Link-Ziele (Karte 345).
 *
 * <p>Ein Modul, das aus einer Mail (oder sonstwoher) direkt auf einen konkreten Datensatz
 * verlinken will, registriert dafuer eine Spring-Bean, die dieses Interface implementiert.
 * Der Root-Mechanismus ({@code /deeplink}) uebernimmt dann:
 * <ol>
 *   <li>Anmeldung erzwingen (und den Deep-Link ueber den Login-Flow durchreichen),</li>
 *   <li>pruefen, ob der Benutzer auf das Ziel-Mandat ueberhaupt Zugriff hat,</li>
 *   <li>auf das Ziel-Mandat umschalten,</li>
 *   <li>{@link #isAccessible(String, String)} fragen — die <em>serverseitige</em> Pruefung, ob der
 *       Benutzer diesen konkreten Datensatz sehen darf,</li>
 *   <li>auf {@link #getView()} weiterleiten und die Id als View-Parameter mitgeben.</li>
 * </ol>
 *
 * <h2>Sicherheitsvertrag (bitte genau lesen)</h2>
 * <ul>
 *   <li>Ein Deep-Link <b>verleiht keine Berechtigung</b>. Er ist nur eine Navigationshilfe. Die
 *       Ziel-Seite muss ihren Datensatz weiterhin selbst mandantengetrennt laden — die Pruefung
 *       hier ersetzt das nicht, sie verhindert nur, dass der Link ueberhaupt dorthin fuehrt.</li>
 *   <li>{@link #isAccessible(String, String)} muss <b>fail-closed</b> sein: im Zweifel
 *       {@code false}. Wirft die Methode, wertet der Root-Mechanismus das als Ablehnung.</li>
 *   <li>Die Methode muss gegen <b>geratene Ids</b> schuetzen: es genuegt nicht zu pruefen, ob der
 *       Datensatz existiert — er muss zum uebergebenen Mandat gehoeren (und, wo das Modul
 *       feingranulare Rechte kennt, fuer den aktuellen Benutzer sichtbar sein).</li>
 *   <li>{@link #getView()} kommt <b>aus dem Server</b>, nie aus der URL. Damit kann ein
 *       manipulierter Link kein beliebiges Ziel ansteuern (kein Open Redirect).</li>
 * </ul>
 */
public interface DeepLinkTarget {

    /**
     * Stabiler, technischer Schluessel des Ziels, wie er im Link als {@code type=} steht —
     * z.B. {@code "auszahlung"}. Nur Kleinbuchstaben, Ziffern, {@code -} und {@code _};
     * andere Werte werden beim Start abgelehnt.
     */
    String getType();

    /**
     * Ziel-View, auf die weitergeleitet wird — der gleiche Wert wie in einer
     * {@code @MenuAnnotation(link=...)}, also z.B. {@code "auszahlungen.html"}. Serverseitig
     * festgelegt, nie aus der URL uebernommen.
     */
    String getView();

    /** Sprechender Name fuer die Root-Uebersicht, z.B. „Auszahlung". */
    String getLabel();

    /** Name des View-Parameters, unter dem die Id an die Ziel-Seite gehaengt wird. */
    default String getParamName() {
        return "id";
    }

    /**
     * <b>Die serverseitige Zugriffspruefung.</b> Darf der aktuell angemeldete Benutzer diesen
     * Datensatz in diesem Mandat sehen?
     *
     * <p>Wird aufgerufen, <em>nachdem</em> auf das Ziel-Mandat gewechselt wurde und bevor
     * weitergeleitet wird — die Module filtern ihre Daten ueber den aktiven Mandanten, eine
     * Pruefung davor wuerde systematisch {@code false} liefern (siehe {@code DeepLinkResolver}).
     * Gewechselt wird nur in ein Mandat, das der Benutzer ohnehin waehlen duerfte; faellt diese
     * Pruefung negativ aus, wird der vorherige Mandat wiederhergestellt. Der Mandat wird
     * zusaetzlich explizit uebergeben, damit die Implementierung dagegen pruefen kann, statt sich
     * allein auf den Session-Zustand zu verlassen.
     *
     * @param mandat Ziel-Mandat, kleingeschrieben; bereits als „Benutzer hat Zugriff darauf" geprueft
     * @param id     Datensatz-Id aus dem Link; bereits gegen ein enges Zeichenmuster validiert,
     *               aber inhaltlich ungeprueft (kann geraten/manipuliert sein)
     * @return {@code true} nur, wenn der Datensatz existiert, zu {@code mandat} gehoert und fuer
     *         den aktuellen Benutzer sichtbar ist
     */
    boolean isAccessible(String mandat, String id);
}
