/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.boot.plugins.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Card 1257: pins the one number that two modules have to agree on.
 *
 * <h2>Why a test for a constant</h2>
 *
 * <p>{@code WatchTokenFilterConfig} in plaintext-root-watch registers its filter at
 * {@code -99} so that it runs <b>inside</b> the {@code springSecurityFilterChain} and therefore
 * sees the authentication. It writes {@code -100} out as a literal, because that module carries
 * no {@code spring-boot-starter-security} and cannot reference the constant.</p>
 *
 * <p>The constant is {@code SecurityFilterProperties.DEFAULT_FILTER_ORDER} — in Spring Boot 4
 * it moved out of {@code SecurityProperties} into its own class, which is exactly the kind of
 * move this test is here to notice. If Spring Boot ever changed the value, the watch filter
 * would land
 * <em>outside</em> the security chain, see no authentication, conclude "not a token session"
 * and wave everything through — without anything going red. This test is what goes red
 * instead.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
class SicherheitsketteOrdnungTest {

    /** Same literal as {@code WatchTokenFilterConfig.SICHERHEITSKETTE_ORDER}. */
    private static final int ERWARTET = -100;

    @Test
    @DisplayName("Die Sicherheitskette laeuft weiterhin bei -100")
    void sicherheitsketteBei100() {
        assertEquals(ERWARTET, SecurityFilterProperties.DEFAULT_FILTER_ORDER,
                "WatchTokenFilterConfig registriert bei -99, damit der Filter INNERHALB der "
                        + "Sicherheitskette laeuft. Verschiebt sich diese Zahl, laeuft er "
                        + "ausserhalb, sieht keine Anmeldung und laesst alles durch.");
    }
}
