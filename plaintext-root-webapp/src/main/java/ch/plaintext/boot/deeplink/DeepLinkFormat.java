/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

import java.util.regex.Pattern;

/**
 * Character patterns for the three deep-link parameters (card 345) — one source of truth for
 * building links ({@code DeepLinkServiceImpl}) and for the entry point ({@code DeepLinkController}).
 *
 * <p>The patterns are deliberately narrow. They are <b>no</b> access control (that sits in
 * {@code getAllowedMandate()} and {@code DeepLinkTarget#isAccessible}), but they prevent anything
 * other than a plain identifier from reaching the redirect URL in the first place: no
 * {@code //example.com} (open redirect), no {@code ?}/{@code &} (parameter smuggling), no
 * CR/LF (header injection), no {@code <} (reflection into the error page).
 */
public final class DeepLinkFormat {

    /** Technical key of a target. */
    public static final Pattern TYPE = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    /** Tenant name as it stands in the {@code PROPERTY_MANDAT_} authorities. */
    public static final Pattern MANDAT = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    /**
     * Record id. Deliberately alphanumeric incl. {@code -} and {@code _}, so that both
     * numeric ids and UUIDs fit — but nothing that would have a meaning inside a URL.
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
