/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

import lombok.AllArgsConstructor;
import lombok.Data;

/** UI row of the module list: id, display name, version, on/off state. */
@Data
@AllArgsConstructor
public class ModuleView {
    private String moduleId;
    private String displayName;
    private String version;
    private boolean enabled;
}
