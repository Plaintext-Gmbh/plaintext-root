/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Menu entry "Swagger" — only if the Swagger UI is actually shipped.
 *
 * <p>springdoc is switched off secure-by-default ({@code springdoc.swagger-ui.enabled=false},
 * can be switched on with {@code SPRINGDOC_ENABLED=true}). Until 29.08.2026 the menu entry stood
 * in every root menu regardless: the click ran into a 404, and the {@code PlaintextErrorViewResolver}
 * redirected from there to the dashboard — to the user that looked as if "Swagger" opened the
 * start page. The {@code MenuAnnotationScanner} evaluates the condition against the real environment.</p>
 */
@ConditionalOnProperty(name = "springdoc.swagger-ui.enabled", havingValue = "true")
@MenuAnnotation(
    title = "Swagger",
    link = "swagger-ui/index.html",
    parent = "Root",
    order = 130,
    icon = "pi pi-book",
    roles = {"ROOT"}
)
public class SwaggerMenu {

}
