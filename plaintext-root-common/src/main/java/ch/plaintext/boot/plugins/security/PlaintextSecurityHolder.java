/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security;

import ch.plaintext.PlaintextSecurity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class PlaintextSecurityHolder {

    private static PlaintextSecurity delegate;

    @Autowired
    public void setDelegate(PlaintextSecurity security) {
        PlaintextSecurityHolder.delegate = security;
    }

    public static String getMandat() {
        return delegate.getMandat();
    }

    public static Long getId() {
        return delegate.getId();
    }

    public static String getUser() {
        return delegate.getUser();
    }

    public static org.springframework.security.core.Authentication getAuthentication() {
        return delegate.getAuthentication();
    }

    public static String getMandatForUser(long userId) {
        return delegate.getMandatForUser(userId);
    }
}
