/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.web;

import ch.plaintext.anforderungen.entity.Anforderung;
import ch.plaintext.anforderungen.service.AnforderungService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClaudeSummaryBackingBeanTest {

    @Mock
    private AnforderungService anforderungService;

    private ClaudeSummaryBackingBean bean;

    @BeforeEach
    void setUp() {
        bean = new ClaudeSummaryBackingBean(anforderungService);
    }

    // --- init ---

    @Test
    void initLoadsAnforderungWhenIdIsSet() {
        Anforderung anf = new Anforderung();
        anf.setId(1L);
        anf.setClaudeSummary("Some summary");
        when(anforderungService.findById(1L)).thenReturn(Optional.of(anf));

        bean.setAnforderungId(1L);
        bean.onLoad();

        assertThat(bean.getAnforderung()).isNotNull();
        assertThat(bean.getAnforderung().getClaudeSummary()).isEqualTo("Some summary");
    }

    @Test
    void initDoesNothingWhenIdIsNull() {
        bean.setAnforderungId(null);
        bean.onLoad();

        assertThat(bean.getAnforderung()).isNull();
        verify(anforderungService, never()).findById(any());
    }

    @Test
    void initSetsNullWhenNotFound() {
        when(anforderungService.findById(999L)).thenReturn(Optional.empty());

        bean.setAnforderungId(999L);
        bean.onLoad();

        assertThat(bean.getAnforderung()).isNull();
    }

    // --- getMarkdownHtml ---

    @Test
    void getMarkdownHtmlReturnsNullWhenNoAnforderung() {
        bean.setAnforderungId(null);
        bean.onLoad();

        assertThat(bean.getMarkdownHtml()).isNull();
    }

    @Test
    void getMarkdownHtmlReturnsNullWhenNoSummary() {
        Anforderung anf = new Anforderung();
        anf.setClaudeSummary(null);
        when(anforderungService.findById(1L)).thenReturn(Optional.of(anf));

        bean.setAnforderungId(1L);
        bean.onLoad();

        assertThat(bean.getMarkdownHtml()).isNull();
    }

    @Test
    void getMarkdownHtmlReturnsSummary() {
        Anforderung anf = new Anforderung();
        anf.setClaudeSummary("# Summary\nContent here");
        when(anforderungService.findById(1L)).thenReturn(Optional.of(anf));

        bean.setAnforderungId(1L);
        bean.onLoad();

        assertThat(bean.getMarkdownHtml()).isEqualTo("# Summary\nContent here");
    }

    // --- getMarkdownContentJson ---

    @Test
    void getMarkdownContentJsonReturnsEmptyQuotesWhenNoAnforderung() {
        assertThat(bean.getMarkdownContentJson()).isEqualTo("\"\"");
    }

    @Test
    void getMarkdownContentJsonReturnsEmptyQuotesWhenNoSummary() {
        Anforderung anf = new Anforderung();
        anf.setClaudeSummary(null);
        when(anforderungService.findById(1L)).thenReturn(Optional.of(anf));

        bean.setAnforderungId(1L);
        bean.onLoad();

        assertThat(bean.getMarkdownContentJson()).isEqualTo("\"\"");
    }

    @Test
    void getMarkdownContentJsonReturnsJsonEscapedContent() {
        Anforderung anf = new Anforderung();
        anf.setClaudeSummary("Hello \"world\"");
        when(anforderungService.findById(1L)).thenReturn(Optional.of(anf));

        bean.setAnforderungId(1L);
        bean.onLoad();

        String json = bean.getMarkdownContentJson();
        assertThat(json).contains("Hello");
        assertThat(json).startsWith("\"");
        assertThat(json).endsWith("\"");
    }

    // --- hasSummary ---

    @Test
    void hasSummaryReturnsFalseWhenNoAnforderung() {
        assertThat(bean.hasSummary()).isFalse();
    }

    @Test
    void hasSummaryReturnsFalseWhenSummaryIsNull() {
        Anforderung anf = new Anforderung();
        anf.setClaudeSummary(null);
        when(anforderungService.findById(1L)).thenReturn(Optional.of(anf));

        bean.setAnforderungId(1L);
        bean.onLoad();

        assertThat(bean.hasSummary()).isFalse();
    }

    @Test
    void hasSummaryReturnsFalseWhenSummaryIsEmpty() {
        Anforderung anf = new Anforderung();
        anf.setClaudeSummary("   ");
        when(anforderungService.findById(1L)).thenReturn(Optional.of(anf));

        bean.setAnforderungId(1L);
        bean.onLoad();

        assertThat(bean.hasSummary()).isFalse();
    }

    @Test
    void hasSummaryReturnsTrueWhenSummaryExists() {
        Anforderung anf = new Anforderung();
        anf.setClaudeSummary("Real content");
        when(anforderungService.findById(1L)).thenReturn(Optional.of(anf));

        bean.setAnforderungId(1L);
        bean.onLoad();

        assertThat(bean.hasSummary()).isTrue();
    }

    // --- onLoad guards ---

    @Test
    void onLoadWithNullFacesContextDoesNotThrow() {
        bean.setAnforderungId(null);
        // No FacesContext in the unit test: getCurrentInstance() returns null; onLoad() has to tolerate that.
        bean.onLoad();

        assertThat(bean.getAnforderung()).isNull();
    }
}
