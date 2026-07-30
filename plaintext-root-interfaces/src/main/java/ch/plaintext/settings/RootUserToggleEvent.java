/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.settings;

import org.springframework.context.ApplicationEvent;

public class RootUserToggleEvent extends ApplicationEvent {

    private final boolean enabled;

    public RootUserToggleEvent(Object source, boolean enabled) {
        super(source);
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
