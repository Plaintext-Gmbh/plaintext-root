/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * Menuepunkt „Swagger" — nur wenn die Swagger-UI auch ausgeliefert wird.
 *
 * <p>springdoc ist secure-by-default abgeschaltet ({@code springdoc.swagger-ui.enabled=false},
 * per {@code SPRINGDOC_ENABLED=true} einschaltbar). Bis zum 29.08.2026 stand der Menuepunkt
 * trotzdem in jedem Root-Menue: der Klick lief auf 404, und der {@code PlaintextErrorViewResolver}
 * leitete von dort auf das Dashboard — fuer den Benutzer sah das aus, als oeffne „Swagger" die
 * Startseite. Der {@code MenuAnnotationScanner} wertet die Bedingung gegen die echte Umgebung aus.</p>
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
