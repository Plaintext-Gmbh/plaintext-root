/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.resource;

/**
 * Die Marke, die jede JSF-Ressourcenadresse mitfuehrt — Karte 1311.
 *
 * <h2>Der Befund</h2>
 *
 * <p>Am 20.09.2026 an PROD gemessen:
 *
 * <pre>
 *   GET https://app.plaintext.ch/jakarta.faces.resource/watch.css.html?ln=watch
 *       cache-control: max-age=604800      (sieben Tage)
 *       etag:          W/"15035-1789899521770"
 * </pre>
 *
 * <p>Der Server lieferte den richtigen Inhalt. Die Adresse traegt aber <b>keinen
 * Versionsanteil</b>: sie lautet nach jeder Aenderung gleich, und mit sieben Tagen Frist fragt
 * der Browser sieben Tage lang gar nicht erst nach. Der {@code etag} hilft dabei nicht — er
 * wird nur ausgewertet, wenn ueberhaupt eine Anfrage rausgeht. Daniel hat deshalb am
 * 20.09.2026 zwei Aenderungen als „nicht umgesetzt" gemeldet, die seit dem Vortag in PROD
 * standen.
 *
 * <h2>Warum diese Loesung und nicht die beiden naheliegenden</h2>
 *
 * <p><b>Nicht die JSF-Ressourcenversionierung ueber Versionsordner.</b> JSF haengt ein
 * {@code &v=1_0_2} an, wenn die Datei unter {@code META-INF/resources/watch/1_0_2/watch.css}
 * liegt. Diese Nummer ist ein <i>Verzeichnisname</i> und laesst sich an nichts koppeln: sie
 * muesste bei jeder Aenderung von Hand hochgezaehlt werden, in jedem Modul einzeln, in zwei
 * Repositories. Eine Marke, die man vergessen kann, ist schlimmer als gar keine — man verlaesst
 * sich dann darauf.
 *
 * <p><b>Nicht die Frist senken.</b> {@code max-age} auf Stunden statt Tage kostet je
 * Seitenaufruf eine Rueckfrage ueber eine Mobilverbindung und laesst trotzdem ein Fenster
 * offen, in dem der alte Stand ausgeliefert wird. Die Frist ist nicht falsch — die Adresse war
 * es.
 *
 * <p><b>Sondern die Projektversion in der Adresse.</b> Sie steigt bei jedem Release ohnehin,
 * niemand muss daran denken, und sie gilt fuer <i>jede</i> ueber {@code library=}/{@code name=}
 * eingebundene Ressource jeder Anwendung — also auch fuer {@code plaintext-app.css}, die
 * denselben Fehler hat (Karte 1286). Keine Seite und kein Modul aendert sich dafuer.
 *
 * <h2>Warum ein statisches Feld</h2>
 *
 * <p>Den {@code ResourceHandler} baut JSF, nicht Spring; er kann sich keine Bohne spritzen
 * lassen. Der Wert wird darum beim Hochfahren einmal von {@code WebAutoConfiguration}
 * hereingereicht und danach nur gelesen. Dass er ueberhaupt setzbar ist, ist zugleich die
 * Voraussetzung der Messung, die Karte 1311 verlangt: <b>zwei Abrufe mit einer Aenderung
 * dazwischen</b> — ein Release, ohne die Anwendung neu zu starten.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public final class RessourcenStand {

    /**
     * Laenge, ab der eine Marke abgeschnitten wird. Sie steht in jeder Ressourcenadresse jeder
     * Seite; eine Versionszeichenkette ist deutlich kuerzer, und was laenger ist, ist keine.
     */
    static final int MAX_LAENGE = 40;

    /**
     * Rueckfall und zugleich Zusatz fuer SNAPSHOT-Staende: die Startzeit der JVM, kurz
     * geschrieben. In der Entwicklung bleibt die Version ueber hunderte Baue dieselbe — eine
     * feste Marke stellte dort genau den Fehler wieder her, den diese Klasse behebt. Mit der
     * Startzeit sieht der Entwickler seine Aenderung nach jedem Neustart.
     */
    private static final String STARTMARKE = Long.toString(System.currentTimeMillis() / 1000L, 36);

    private static volatile String stand = STARTMARKE;

    private RessourcenStand() {
        // Werkzeugklasse.
    }

    /** @return die aktuelle Marke, nie leer. */
    public static String stand() {
        return stand;
    }

    /**
     * Uebernimmt die Projektversion als Marke.
     *
     * @param version {@code plaintext.version}; leer oder {@code null} laesst die Startmarke stehen
     */
    public static void setze(String version) {
        stand = saeubere(version);
    }

    /**
     * Macht aus einer Versionszeichenkette etwas, das ohne Kodierung in einer Adresse stehen darf.
     *
     * @param version die Rohfassung
     * @return die Marke
     */
    static String saeubere(String version) {
        if (version == null || version.isBlank()) {
            return STARTMARKE;
        }
        String roh = version.trim();
        if (roh.endsWith("-SNAPSHOT")) {
            roh = roh + "-" + STARTMARKE;
        }
        String sauber = roh.replaceAll("[^A-Za-z0-9._-]", "-");
        return sauber.length() > MAX_LAENGE ? sauber.substring(0, MAX_LAENGE) : sauber;
    }
}
