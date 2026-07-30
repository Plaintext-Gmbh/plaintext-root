/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Root menu entry for API Token management (all tokens across all mandats).
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@MenuAnnotation(
    title = "API Tokens (Root)",
    link = "root-api-token.html",
    order = 120,
    parent = "Root",
    icon = "pi pi-key",
    roles = {"ROOT"}
)
public class RootApiTokenMenu {
}
