/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) eMad, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Admin menu entry for API Token management (all tokens in mandat).
 *
 * @author Plaintext GmbH
 * @since 2026
 */
@MenuAnnotation(
    title = "API Tokens",
    link = "admin-api-token.html",
    order = 30,
    parent = "Admin",
    icon = "pi pi-key",
    roles = {"ADMIN", "ROOT"}
)
public class AdminApiTokenMenu {
}
