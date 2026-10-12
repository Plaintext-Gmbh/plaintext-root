/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.telefon;

import ch.plaintext.arch.StabileApi;
import java.util.regex.Pattern;

/**
 * Karte 593: Dieselbe Telefonnummer, fünf Schreibweisen — <b>eine</b> Vergleichsform.
 *
 * <p><b>Der gemessene Defekt, gegen den diese Klasse entstand.</b> Das Messenger-Modul bildete
 * WhatsApp-Chat-IDs bisher mit einer Normalisierung, die nur Nicht-Ziffern und ein führendes
 * {@code 00} entfernte. Eine <b>national</b> erfasste Nummer behielt damit ihre führende Null:
 * aus {@code 079 291 40 31} wurde die JID {@code 0792914031@s.whatsapp.net}, während die echte
 * WhatsApp-JID {@code 41792914031@s.whatsapp.net} lautet. Die Kontaktauflösung lief für solche
 * Nummern ins Leere. Am Produktivbestand gezählt (06.08.2026): <b>53 von 236</b> erfassten
 * Nummern sind national geschrieben — 22 % waren damit unsichtbar.
 *
 * <p><b>Warum die Klasse hier liegt und nicht im Messenger.</b> Sie wird von zwei z-Modulen
 * gebraucht (Messenger für die JID-Bildung, Kontakte für {@code find_contact_by_phone}), und
 * z-Module dürfen nicht direkt voneinander abhängen ({@code ModuleDependencyTest}). Zwei Kopien
 * derselben Regel wären genau die stille Divergenz, an der eine Suche später ohne Fehlermeldung
 * das Falsche findet.
 *
 * <p><b>Warum eine Konstante und keine Spring-Property.</b> Dieses Modul hat kein spring-boot auf
 * dem Classpath, {@code @ConfigurationProperties} ist hier also gar nicht verfügbar; zwei getrennt
 * gelesene Properties in zwei Modulen wären wieder zwei Wahrheiten. Wer eine andere Landesvorwahl
 * braucht, nimmt {@link #normalisiere(String, String)} — die Regel bleibt dieselbe.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@StabileApi("Normalisierung von Telefonnummern (Karte 1504, aus app-interfaces)")
public final class Telefonnummern {

    /** Landesvorwahl, mit der eine führende 0 ersetzt wird (Schweiz). */
    public static final String DEFAULT_LANDESVORWAHL = "41";

    /** Kürzeste Ziffernfolge, die für den Suffix-Vergleich noch aussagekräftig ist. */
    private static final int SUFFIX_LAENGE = 9;

    private static final Pattern NICHT_ZIFFER = Pattern.compile("\\D");

    private Telefonnummern() {
    }

    /**
     * Normalisiert eine Rufnummer auf die reine internationale Ziffernfolge (E.164 ohne {@code +}),
     * mit {@link #DEFAULT_LANDESVORWAHL} für national geschriebene Nummern.
     *
     * @param roh Nummer in beliebiger Schreibweise, darf {@code null} sein
     * @return Ziffernfolge ohne Trennzeichen, oder {@code ""} wenn nichts Verwertbares übrig bleibt
     */
    public static String normalisiere(String roh) {
        return normalisiere(roh, DEFAULT_LANDESVORWAHL);
    }

    /**
     * Wie {@link #normalisiere(String)}, aber mit frei gewählter Landesvorwahl.
     *
     * <p>Behandelt der Reihe nach: alle Nicht-Ziffern entfernen ({@code +}, Leerzeichen, Bindestrich,
     * Klammern) — führendes {@code 00} (Amtskennung) entfernen — führende {@code 0} durch die
     * Landesvorwahl ersetzen.
     *
     * @param roh            Nummer in beliebiger Schreibweise, darf {@code null} sein
     * @param landesvorwahl  Vorwahl ohne {@code +}, z. B. {@code "41"}; ist sie leer, bleibt eine
     *                       führende 0 unverändert stehen (es wird nicht geraten)
     * @return Ziffernfolge ohne Trennzeichen, nie {@code null}
     */
    public static String normalisiere(String roh, String landesvorwahl) {
        if (roh == null) {
            return "";
        }
        String ziffern = NICHT_ZIFFER.matcher(roh).replaceAll("");
        if (ziffern.startsWith("00")) {
            ziffern = ziffern.substring(2);
        } else if (ziffern.startsWith("0") && landesvorwahl != null && !landesvorwahl.isBlank()) {
            ziffern = landesvorwahl.trim() + ziffern.substring(1);
        }
        return ziffern;
    }

    /**
     * Vergleicht zwei Nummern über ihre normalisierte Form. Leere/nicht verwertbare Werte sind nie
     * gleich — sonst würde ein Kontakt ohne Nummer auf jede Suche passen.
     */
    public static boolean gleich(String a, String b) {
        String na = normalisiere(a);
        return !na.isEmpty() && na.equals(normalisiere(b));
    }

    /**
     * Die letzten {@value #SUFFIX_LAENGE} Ziffern — <b>Fallback</b> für Nummern, deren Länderteil
     * nicht rekonstruierbar ist.
     *
     * <p><b>Bewusst kein Standardweg:</b> bei ausländischen Nummern erzeugt der Vergleich der
     * letzten Ziffern Falschtreffer. Er gehört nur dorthin, wo ein Treffer dem Benutzer
     * <em>vorgeschlagen</em> und als unsicher gekennzeichnet wird — nicht dorthin, wo automatisch
     * zugeordnet wird.
     *
     * @return die letzten {@value #SUFFIX_LAENGE} Ziffern, oder {@code ""} wenn die Nummer kürzer ist
     */
    public static String suffix(String roh) {
        String ziffern = normalisiere(roh);
        return ziffern.length() < SUFFIX_LAENGE ? "" : ziffern.substring(ziffern.length() - SUFFIX_LAENGE);
    }

    /**
     * {@code true}, wenn beide Nummern auf denselben {@link #suffix(String)} enden — der
     * dokumentierte Fallback zu {@link #gleich(String, String)}, mit demselben Falschtreffer-Risiko.
     */
    public static boolean gleichesEnde(String a, String b) {
        String sa = suffix(a);
        return !sa.isEmpty() && sa.equals(suffix(b));
    }
}
