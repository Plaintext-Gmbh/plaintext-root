/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

import java.util.regex.Pattern;

/**
 * Zeichenmuster fuer die drei Deep-Link-Parameter (Karte 345) — eine Quelle der Wahrheit fuer den
 * Link-Bau ({@code DeepLinkServiceImpl}) und den Einstiegspunkt ({@code DeepLinkController}).
 *
 * <p>Die Muster sind bewusst eng. Sie sind <b>keine</b> Zugriffskontrolle (die steckt in
 * {@code getAllowedMandate()} und {@code DeepLinkTarget#isAccessible}), sondern verhindern, dass
 * ueberhaupt etwas anderes als ein schlichter Bezeichner in die Redirect-URL gelangt: kein
 * {@code //example.com} (Open Redirect), kein {@code ?}/{@code &} (Parameter-Schmuggel), kein
 * CR/LF (Header-Injection), kein {@code <} (Reflexion in die Fehlerseite).
 */
public final class DeepLinkFormat {

    /** Technischer Schluessel eines Ziels. */
    public static final Pattern TYPE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    /** Mandat-Name, wie er in den {@code PROPERTY_MANDAT_}-Authorities steht. */
    public static final Pattern MANDAT = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    /**
     * Datensatz-Id. Absichtlich alphanumerisch inkl. {@code -} und {@code _}, damit sowohl
     * numerische Ids als auch UUIDs passen — aber nichts, was in einer URL eine Bedeutung haette.
     */
    public static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    private DeepLinkFormat() {
    }

    public static boolean istGueltigerType(String wert) {
        return wert != null && TYPE.matcher(wert).matches();
    }

    public static boolean istGueltigesMandat(String wert) {
        return wert != null && MANDAT.matcher(wert).matches();
    }

    public static boolean istGueltigeId(String wert) {
        return wert != null && ID.matcher(wert).matches();
    }
}
