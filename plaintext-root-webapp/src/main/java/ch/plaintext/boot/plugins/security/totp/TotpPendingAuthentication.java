/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.totp;

import org.springframework.security.core.Authentication;

import java.io.Serializable;

/**
 * Carrier for the "password ok, second factor pending" intermediate state. It is stored in the
 * HTTP session after a successful password login (NOT in the SecurityContext) and is only
 * turned into a full authentication after a valid TOTP/recovery code.
 *
 * <p><b>Security invariant:</b> as long as this object lies in the session and the
 * actual authentication is NOT in the SecurityContext, the request context counts as
 * anonymous - every access to protected resources is rejected. The second
 * factor therefore cannot be bypassed.
 *
 * @param authentication the withheld, fully resolved authentication
 * @param username       login name (for verification/rate limit)
 * @param targetUrl      originally intended target (start page), to which the user is
 *                       redirected after success
 */
public record TotpPendingAuthentication(Authentication authentication, String username, String targetUrl)
        implements Serializable {

    /** Session attribute key for the pending two-factor state. */
    public static final String SESSION_ATTRIBUTE = "PLAINTEXT_TOTP_PENDING_AUTH";
}
