/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch;

import org.junit.jupiter.api.Test;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Card 546: records that Spring method security stays switched on in root.
 *
 * <p>Without {@code @EnableMethodSecurity} every {@code @PreAuthorize} is <b>silently ignored</b> —
 * the annotation stands in the code, looks like a barrier and lets everybody through. That this is no
 * theoretical risk is proven by the existing code itself: two places checked in the
 * method body instead of annotating for exactly that reason, each with a written justification
 * ({@code PlaintextSecurityImpl:501} "would therefore be annotated silently without effect",
 * {@code I18nExportController:49} "the annotation would be a silent dummy").
 *
 * <p>With card 546 app, guild and root migrate their MCP tools to {@code @PreAuthorize} gates,
 * safeguarded by {@link ch.plaintext.arch.PlaintextMcpScopeVertragTest}. If this
 * switch disappears again, all of them become ineffective in one stroke — and the contract test would
 * stay green, because it checks the <em>annotation</em> and not its <em>effect</em>. Exactly that gap
 * is closed by this test. The model is schuetu's test of the same name (audit finding C-4); app and
 * guild have had it since {@code plaintext-app#566} resp. {@code plaintext-guild#112}.
 *
 * <p>Boots no DB.
 */
class MethodSecurityEnabledTest {

    @Test
    void mainKlasseAktiviertMethodSecurity() {
        assertNotNull(
                RootBootApplication.class.getAnnotation(EnableMethodSecurity.class),
                "@EnableMethodSecurity muss aktiv sein, sonst werden @PreAuthorize-Gates still ignoriert");
    }
}
