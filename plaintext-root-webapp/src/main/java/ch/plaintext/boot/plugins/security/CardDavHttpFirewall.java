/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.firewall.DefaultHttpFirewall;
import org.springframework.security.web.firewall.FirewalledRequest;

/**
 * Custom HTTP Firewall to allow CardDAV methods (PROPFIND, REPORT)
 *
 * @author info@plaintext.ch
 * @since 2024
 */
public class CardDavHttpFirewall extends DefaultHttpFirewall {

    @Override
    public FirewalledRequest getFirewalledRequest(HttpServletRequest request) {
        String method = request.getMethod();

        // Allow PROPFIND and REPORT methods for CardDAV
        if ("PROPFIND".equalsIgnoreCase(method) || "REPORT".equalsIgnoreCase(method)) {
            return new FirewalledRequest(request) {
                @Override
                public String getMethod() {
                    return method.toUpperCase();
                }

                @Override
                public void reset() {
                    // No-op for CardDAV methods
                }
            };
        }
        return super.getFirewalledRequest(request);
    }
}
