/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Menu entry for API Token management
 *
 * @author Plaintext GmbH
 * @since 2026
 */
@MenuAnnotation(
    title = "API Token",
    link = "api-token.html",
    order = 90,
    parent = "Admin",
    icon = "pi pi-key",
    roles = {"USER", "ADMIN", "ROOT"}
)
public class ApiTokenMenu {
}
