/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.web;

import ch.plaintext.anforderungen.entity.Anforderung;
import ch.plaintext.anforderungen.service.AnforderungService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.faces.context.FacesContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * Backing Bean for Claude Summary display page
 */
@Component
@Scope("session")
@Getter
@Setter
@Slf4j
public class ClaudeSummaryBackingBean implements Serializable {

    private final AnforderungService anforderungService;

    private Long anforderungId;
    private Anforderung anforderung;

    public ClaudeSummaryBackingBean(AnforderungService anforderungService) {
        this.anforderungService = anforderungService;
    }

    /**
     * preRenderView-Listener (session-scoped): laedt die Anforderung anhand des viewParam {@code id} bei jedem GET.
     * viewParams sind vor preRenderView gesetzt, daher ist {@code anforderungId} hier verfuegbar. Der isPostback-Guard
     * verhindert das Neuladen bei Ajax-Postbacks. Ersetzt das fruehere init() + @PostConstruct postConstruct().
     */
    public void onLoad() {
        FacesContext ctx = FacesContext.getCurrentInstance();
        if (ctx != null && ctx.isPostback()) {
            return;
        }
        log.info("ClaudeSummaryBackingBean.onLoad() called with anforderungId: {}", anforderungId);
        if (anforderungId != null) {
            loadAnforderung();
        } else {
            log.warn("onLoad() called but anforderungId is null");
        }
    }

    private void loadAnforderung() {
        if (anforderungId != null) {
            try {
                anforderung = anforderungService.findById(anforderungId).orElse(null);
                if (anforderung == null) {
                    log.warn("Anforderung not found: {}", anforderungId);
                } else {
                    String summaryPreview = anforderung.getClaudeSummary() != null
                        ? anforderung.getClaudeSummary().substring(0, Math.min(50, anforderung.getClaudeSummary().length()))
                        : "NULL";
                    log.info("Loaded anforderung {} with summary exists: {}, length: {}, preview: {}",
                            anforderungId,
                            anforderung.getClaudeSummary() != null ? "YES" : "NO",
                            anforderung.getClaudeSummary() != null ? anforderung.getClaudeSummary().length() : 0,
                            summaryPreview);
                }
            } catch (Exception e) {
                log.error("Error loading anforderung: {}", anforderungId, e);
            }
        } else {
            log.warn("anforderungId is null in loadAnforderung()");
        }
    }

    /**
     * Get the markdown HTML content (already rendered if available)
     */
    public String getMarkdownHtml() {
        log.debug("getMarkdownHtml() called: anforderung={}, summary={}",
                anforderung != null ? anforderung.getId() : "null",
                anforderung != null && anforderung.getClaudeSummary() != null ? "exists (length=" + anforderung.getClaudeSummary().length() + ")" : "null");
        if (anforderung == null || anforderung.getClaudeSummary() == null) {
            return null;
        }
        return anforderung.getClaudeSummary();
    }

    /**
     * Get markdown content as JSON-escaped string for JavaScript
     */
    public String getMarkdownContentJson() {
        if (anforderung == null || anforderung.getClaudeSummary() == null) {
            return "\"\"";
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(anforderung.getClaudeSummary());
        } catch (Exception e) {
            log.error("Error converting markdown to JSON", e);
            return "\"\"";
        }
    }

    /**
     * Check if summary exists
     */
    public boolean hasSummary() {
        boolean result = anforderung != null
            && anforderung.getClaudeSummary() != null
            && !anforderung.getClaudeSummary().trim().isEmpty();
        log.info("hasSummary() called: anforderung={}, claudeSummary={}, isEmpty={}, result={}",
                anforderung != null ? anforderung.getId() : "null",
                anforderung != null && anforderung.getClaudeSummary() != null ? "exists (length=" + anforderung.getClaudeSummary().length() + ")" : "null",
                anforderung != null && anforderung.getClaudeSummary() != null ? anforderung.getClaudeSummary().trim().isEmpty() : "N/A",
                result);
        return result;
    }
}
