/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.freigabe;

import ch.plaintext.modules.ModulApi;

import java.nio.charset.StandardCharsets;

/**
 * Response of a {@link FreigabeQuelle} (card 1476). root sets the security headers itself (no cache, no
 * referrer, noindex, nosniff) and the CSP: the module's one or, if it is missing, {@link #STRENG}.
 *
 * @param contentType e.g. {@code text/html;charset=UTF-8}
 * @param inhalt      response body
 * @param csp         Content-Security-Policy, or {@code null} for {@link #STRENG}
 */
@ModulApi(art = ModulApi.Art.DTO)
public record FreigabeInhalt(String contentType, byte[] inhalt, String csp) {

    /** No script, nothing loaded from elsewhere, images and fonts inline only, an opaque origin through {@code sandbox}. */
    public static final String STRENG = "default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:; "
            + "frame-ancestors 'none'; form-action 'none'; sandbox";

    /** HTML with the strict CSP. */
    public static FreigabeInhalt html(String html) {
        return new FreigabeInhalt("text/html;charset=UTF-8", html.getBytes(StandardCharsets.UTF_8), null);
    }
}
