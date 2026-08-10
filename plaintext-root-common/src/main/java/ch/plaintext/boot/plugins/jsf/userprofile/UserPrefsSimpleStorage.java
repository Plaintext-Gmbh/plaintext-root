/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.jsf.userprofile;

import ch.plaintext.boot.plugins.objstore.GenericEntityService;
import org.springframework.stereotype.Service;

@Service
public class UserPrefsSimpleStorage extends GenericEntityService<UserPreference> {

}