/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.mcp;

import ch.plaintext.sidecars.entity.SpeicherAblage;
import ch.plaintext.sidecars.service.SpeicherAblageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Karte 1471: Rechte von set_speicher_ablage für die Art GIT (Positiv- und Negativprobe). */
class SpeicherAblagenMcpToolsTest {

    final SpeicherAblageService service = mock(SpeicherAblageService.class);
    final SpeicherAblagenMcpTools tools = new SpeicherAblagenMcpTools(service);

    @AfterEach
    void aufraeumen() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    String setGit() {
        return tools.setSpeicherAblage("git-a", "https://example.invalid/r.git", "u", "t", "", "GIT", "main");
    }

    @Test
    @DisplayName("Ohne ROOT oder ohne SCOPE_ADMIN wird keine Git-Ablage eingerichtet")
    void negativ() {
        SidecarsMcpToolsTest.als("ROLE_ADMIN", "SCOPE_ADMIN");
        assertThat(setGit()).startsWith("FEHLER").contains("ROOT");
        SidecarsMcpToolsTest.als("ROLE_ROOT", "SCOPE_READ");
        assertThat(setGit()).startsWith("FEHLER").contains("scope=ADMIN");
        verify(service, never()).speichere(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("ROOT mit SCOPE_ADMIN richtet sie ein, Art und Zweig kommen beim Service an")
    void positiv() {
        SpeicherAblage s = new SpeicherAblage();
        s.setOk(true);
        s.setMeldung("Verbindung in Ordnung");
        when(service.speichere("git-a", "GIT", "https://example.invalid/r.git", "u", "t", "", "main")).thenReturn(s);
        SidecarsMcpToolsTest.als("ROLE_ROOT", "SCOPE_ADMIN");
        assertThat(setGit()).isEqualTo("OK: Verbindung in Ordnung");
    }
}
