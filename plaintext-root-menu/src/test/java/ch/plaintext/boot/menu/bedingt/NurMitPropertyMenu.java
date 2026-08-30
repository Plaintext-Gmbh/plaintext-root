/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu.bedingt;

import ch.plaintext.boot.menu.MenuAnnotation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Test fixture: a menu item that only exists with {@code test.menu.feature=true} — the pattern
 * of {@code SwaggerMenu} ({@code springdoc.swagger-ui.enabled}).
 */
@ConditionalOnProperty(name = "test.menu.feature", havingValue = "true")
@MenuAnnotation(title = "Nur mit Property", link = "bedingt.html", parent = "Root", order = 2)
public class NurMitPropertyMenu {
}
