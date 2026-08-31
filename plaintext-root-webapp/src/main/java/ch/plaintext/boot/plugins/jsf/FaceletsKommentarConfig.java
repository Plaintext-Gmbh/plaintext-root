/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.boot.plugins.jsf;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Switches {@code FACELETS_SKIP_COMMENTS} on: XML comments are removed while the page is compiled
 * instead of turning into components.
 *
 * <h2>What that protects against</h2>
 *
 * <p>By default a comment in a Facelets page is <b>not</b> a comment but a
 * {@code UIInstruction} in the component tree. Inside an {@code h:panelGrid} it therefore occupies a
 * <b>cell</b> like any other component. A grid with {@code columns="2"} fills its rows
 * one after the other, so a single comment shifts everything that follows by one cell: from there on
 * the label stands in the input column and the input field in the label column.
 *
 * <p><b>The finding of 29.08.2026.</b> In {@code useradmin.xhtml} an explanatory comment stood
 * between the row "Benutzername" and the row "Vorname". From "Vorname" on the
 * form tipped over: the labels slipped into the right column and one row down, the
 * fields into the left one. The form was usable, but labelled wrongly, and nobody looks for the
 * cause in a comment. A scan across root, app and guild found <b>16</b> grids with
 * comments or a bare {@code <br/>} between the cells.
 *
 * <p><b>Why this stands here and not in every application.</b> Set as a servlet parameter, the
 * setting applies to every app built on this module. Via {@code application.yml} it would not:
 * app, guild, iot and schuetu bring their own, and of two files with the same name on the classpath
 * exactly one wins. A protective measure that works depending on the load order is
 * no protective measure.
 *
 * <p><b>What else changes.</b> The comments no longer leave the server. Until now they
 * stood in the delivered HTML — including card numbers, references and justifications written for
 * developers and not for visitors. That is no hole, but no gain either,
 * and it costs bytes on every delivery.
 *
 * <p>Whoever deliberately wants a comment in the HTML (a conditional comment, say) writes it
 * as output, not as a source comment. {@code <ui:remark>} always stays server-side,
 * independently of this.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
@Configuration
public class FaceletsKommentarConfig {

    /** Name according to Jakarta Faces 4 ({@code jakarta.faces.FACELETS_SKIP_COMMENTS}). */
    static final String PARAMETER = "jakarta.faces.FACELETS_SKIP_COMMENTS";

    /**
     * Sets the parameter, provided the application has not already set it itself.
     *
     * <p>The check is not decoration: an application that deliberately sets the value to
     * {@code false} shall keep it. A framework that silently overrides the decision of its consumers
     * is worse than the defect it wants to prevent.
     */
    @Bean
    public ServletContextInitializer faceletsKommentareUeberspringen() {
        return servletContext -> {
            String vorhanden = servletContext.getInitParameter(PARAMETER);
            if (vorhanden != null && !vorhanden.isBlank()) {
                log.info("FACELETS_SKIP_COMMENTS ist bereits auf '{}' gesetzt — bleibt so.", vorhanden);
                return;
            }
            servletContext.setInitParameter(PARAMETER, "true");
        };
    }
}
