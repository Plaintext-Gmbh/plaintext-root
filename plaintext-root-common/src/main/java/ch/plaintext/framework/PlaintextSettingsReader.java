/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import java.util.Date;
import java.util.List;

/**
 * @author Plaintext GmbH
 * @since 0.0.1
 */
public interface PlaintextSettingsReader {

    String getString(String key, String mandat);

    Date getDate(String key, String mandat);

    int getInt(String key, String mandat);

    boolean getBoolean(String key, String mandat);

    List<String> getListe(String key, String mandat);

}
