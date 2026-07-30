/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

/**
 * Base class for programmatic menu items
 */
public abstract class AbstractMenuItem {

    public int getOrder() {
        return 100;
    }

    public abstract String getTitle();

    public abstract String getParent();

    public abstract String getCommand();

    public Integer getRight() {
        return 0;
    }

    public String getIc() {
        return "";
    }

    public boolean isOn() {
        return true;
    }
}
