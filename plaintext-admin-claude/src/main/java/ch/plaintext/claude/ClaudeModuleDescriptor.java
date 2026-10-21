/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.claude;

import ch.plaintext.modules.ModuleDescriptor;
import org.springframework.stereotype.Component;

/** Registers the Claude module with the central module management. */
@Component
public class ClaudeModuleDescriptor implements ModuleDescriptor {
    @Override
    public String moduleId() {
        return "claude";
    }

    @Override
    public String displayName() {
        return "Claude API";
    }
}
