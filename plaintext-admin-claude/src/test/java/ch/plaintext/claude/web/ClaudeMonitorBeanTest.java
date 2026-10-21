/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.claude.web;

import ch.plaintext.claude.ClaudeAnfrage;
import ch.plaintext.claude.ClaudeAntwort;
import ch.plaintext.claude.ClaudeException;
import ch.plaintext.claude.service.AnthropicClaudeClient;
import ch.plaintext.claude.service.ClaudeNutzung;
import ch.plaintext.claude.service.ClaudeNutzungSpeicher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClaudeMonitorBeanTest {

    private final AnthropicClaudeClient client = mock(AnthropicClaudeClient.class);
    private final ClaudeNutzungSpeicher speicher = mock(ClaudeNutzungSpeicher.class);
    private final ClaudeMonitorBean bean = new ClaudeMonitorBean(client, speicher);

    @Test
    void testfrageIstKurzUndZeigtDieAntwort() {
        when(client.frage(any(ClaudeAnfrage.class)))
                .thenReturn(new ClaudeAntwort(" Ja ", "claude-sonnet-5-5", "end_turn", 15, 2, 0, "req"));

        bean.testfrage();

        ArgumentCaptor<ClaudeAnfrage> a = ArgumentCaptor.forClass(ClaudeAnfrage.class);
        verify(client).frage(a.capture());
        assertEquals(20, a.getValue().maxTokens());
        assertEquals(ClaudeMonitorBean.QUELLE, a.getValue().quelle());
        assertEquals("claude-sonnet-5-5: „Ja“ (15 in / 2 out, end_turn)", bean.getTestAntwort());
    }

    @Test
    void testfrageFehlerWirdAngezeigtNichtGeworfen() {
        when(client.frage(any(ClaudeAnfrage.class))).thenThrow(new ClaudeException(ClaudeException.Art.AUTH, "HTTP 401"));
        bean.testfrage();
        assertEquals("Fehler AUTH: HTTP 401", bean.getTestAntwort());
    }

    @Test
    void kostenErfolgUndFehler() {
        AnthropicClaudeClient.Kosten k = new AnthropicClaudeClient.Kosten(LocalDate.now(), LocalDate.now(), BigDecimal.ONE);
        when(client.kosten30Tage()).thenReturn(k).thenThrow(new ClaudeException(ClaudeException.Art.NICHT_KONFIGURIERT, "x"));
        bean.ladeKosten();
        assertEquals(k, bean.getKosten());
        assertNull(bean.getKostenFehler());
        bean.ladeKosten();
        assertNull(bean.getKosten());
        assertTrue(bean.getKostenFehler().startsWith("NICHT_KONFIGURIERT"));
    }

    @Test
    void sichtenLesenDieTabelleNurEinmalJeRequest() {
        when(speicher.ladeAb(any())).thenReturn(List.of(
                new ClaudeNutzung.Zeile(LocalDate.now(), "app", "sonnet", 2, 0, 10, 1, 0, 20)));
        List<ClaudeMonitorBean.Sicht> s = bean.getSichten();
        bean.getSichten();
        assertEquals(3, s.size());
        assertEquals(2, s.get(0).summen().get(0).aufrufe());
        assertEquals("app", s.get(1).summen().get(0).name());
        verify(speicher, times(1)).ladeAb(any());
    }

    @Test
    void reichtZustandDesClientsDurch() {
        when(client.isKonfiguriert()).thenReturn(true);
        when(client.getDefaultModell()).thenReturn("m");
        bean.pruefen();
        verify(client).pruefeVerbindung();
        assertTrue(bean.isKonfiguriert());
        assertEquals("m", bean.getDefaultModell());
        assertNull(bean.getVerbindung());
        assertNull(bean.getRateLimit());
        bean.isAdminKonfiguriert();
    }
}
