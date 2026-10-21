/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.claude.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Root page for the Claude API. ROOT only (also in {@code ROOT_ONLY_PAGES}): the key and the
 * credit belong to the installation, not to a tenant.
 */
@MenuAnnotation(
    title = "Claude API",
    link = "claude-api.html",
    parent = "Root",
    order = 13,
    icon = "pi pi-comments",
    roles = {"ROOT"},
    moduleId = "claude"
)
public class ClaudeMenu {
}
