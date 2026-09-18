/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.menu;

import ch.plaintext.boot.menu.MenuAnnotation;

/**
 * Menu entry for the watch settings.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@MenuAnnotation(
        title = "Watch",
        link = "watch-einstellungen.html",
        order = 95,
        parent = "Admin",
        icon = "pi pi-mobile",
        roles = {"USER", "ADMIN", "ROOT"}
)
public class WatchMenu {
}
