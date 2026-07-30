/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifiziert die SecurityContext-Hygiene von {@link SuperCron#run()}:
 * Background-/Cron-Threads bekommen pro Lauf einen definierten System-Context
 * (SYSTEM-User + Ziel-Mandant), der nach dem Lauf — auch im Exception-Pfad —
 * restlos wieder verschwindet bzw. einen vorher vorhandenen Context restauriert.
 */
@DisplayName("SuperCron SecurityContext-Lifecycle")
class SuperCronSecurityContextTest {

    /** Hält fest, was WÄHREND run(mandant) im SecurityContextHolder lag. */
    private static class ContextCapturingCron extends SuperCron {
        private Authentication seenDuringRun;
        private boolean shouldThrow = false;

        @Override
        public void run(String mandant) {
            seenDuringRun = SecurityContextHolder.getContext().getAuthentication();
            if (shouldThrow) {
                throw new RuntimeException("Cron failed");
            }
        }
    }

    private ContextCapturingCron cron;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        cron = new ContextCapturingCron();
        cron.setBeanName("contextCapturingCron");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void duringRunSystemContextWithMandantIsActive() {
        cron.setMandant("MandatA");

        cron.run();

        assertThat(cron.seenDuringRun).as("Authentication während des Cron-Laufs").isNotNull();
        assertThat(cron.seenDuringRun.getName()).isEqualTo(SuperCron.SYSTEM_USER);
        assertThat(cron.seenDuringRun.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder(SuperCron.ROLE_SYSTEM, "PROPERTY_MANDAT_mandata");
    }

    @Test
    void duringRunGlobalMandantGetsGlobalAuthority() {
        cron.setMandant("global");

        cron.run();

        assertThat(cron.seenDuringRun.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("PROPERTY_MANDAT_global");
    }

    @Test
    void afterRunContextIsCleared() {
        cron.setMandant("mandatA");

        cron.run();

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("Nach dem Lauf darf keine System-Authentication auf dem Thread zurückbleiben")
                .isNull();
    }

    @Test
    void exceptionPathAlsoClearsContext() {
        cron.setMandant("mandatA");
        cron.shouldThrow = true;

        assertThatThrownBy(() -> cron.run())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Cron failed");

        assertThat(cron.seenDuringRun)
                .as("Auch im Exception-Lauf war der System-Context gesetzt")
                .isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("Exception-Pfad muss den Context ebenfalls clearen")
                .isNull();
    }

    @Test
    void preExistingContextIsRestoredAfterRun() {
        Authentication userAuth = new UsernamePasswordAuthenticationToken(
                "realUser", null, List.of(new SimpleGrantedAuthority("PROPERTY_MANDAT_home")));
        SecurityContext userContext = SecurityContextHolder.createEmptyContext();
        userContext.setAuthentication(userAuth);
        SecurityContextHolder.setContext(userContext);

        cron.setMandant("mandatB");
        cron.run();

        assertThat(cron.seenDuringRun.getName())
                .as("Während des Laufs gilt der System-Context, nicht der User-Context")
                .isEqualTo(SuperCron.SYSTEM_USER);
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("Vorher vorhandener Context wird restauriert, nicht gecleart")
                .isSameAs(userAuth);
    }

    @Test
    void blankMandantStillGetsSystemUserWithoutMandatAuthority() {
        cron.setMandant("");

        cron.run();

        assertThat(cron.seenDuringRun.getName()).isEqualTo(SuperCron.SYSTEM_USER);
        assertThat(cron.seenDuringRun.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly(SuperCron.ROLE_SYSTEM);
    }
}
