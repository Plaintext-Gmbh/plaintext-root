/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.framework;

import java.util.List;
import java.util.Set;

public interface PlaintextAuthentication {

    Boolean isAutenticated();

    Boolean hasRole(String role);

    List<PlaintextUser> getAllUsers();

    void saveUser(PlaintextUser user);

    PlaintextUser loadUser();

    PlaintextUser createNewUser();

    String getUsername();

    PlaintextUser getUser(boolean... force);

    Set<String> getAllRoles();

}
