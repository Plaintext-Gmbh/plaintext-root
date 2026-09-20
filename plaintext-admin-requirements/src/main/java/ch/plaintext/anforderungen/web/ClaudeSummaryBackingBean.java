/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.anforderungen.web;

import ch.plaintext.anforderungen.entity.Anforderung;
import ch.plaintext.anforderungen.service.AnforderungService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.faces.context.FacesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;

/**
 * Backing Bean for Claude Summary display page
 *
 * <h2>Why field injection stays here (java:S6813, card 1273)</h2>
 *
 * <p>This bean is <b>session-scoped and serializable</b> — the one case in which constructor
 * injection is the wrong answer. On a deserialization <b>no constructor runs</b>: a service
 * field set only through the
 * constructor stays {@code null} forever, and being {@code final} nothing can set it afterwards,
 * not even the context. A {@code NotSerializableException} would thereby turn into a permanent
 * {@code NullPointerException} (cards 915/1246). Field injection ({@code @Autowired}, not
 * {@code final}) lets the context refill the field after a deserialization — that is the house
 * rule, and {@code PlaintextSessionBeanSerialisierbarTest} enforces it as a build-breaking
 * guard.</p>
 *
 * <p>{@code java:S6813} demands the exact opposite at these fields, so both cannot hold at once.
 * The guard wins: it protects against a defect that is silent and permanent, the rule protects a
 * style. The suppression sits on the class because every injected service of such a bean falls
 * under the house rule — card 1273 carries the measurement and the decision.</p>
 */
@Component
@Scope("session")
@Getter
@Setter
@Slf4j
@SuppressWarnings("java:S6813") // begruendet im Klassenkommentar oben (Karte 1273) — nicht umbauen
public class ClaudeSummaryBackingBean implements Serializable {

    // Feldinjektion, nicht Konstruktorinjektion (Karte 1269). Diese Bohne ist session-scoped und
    // serialisierbar: bei einer Deserialisierung laeuft KEIN Konstruktor. Als `final` gesetzte
    // Dienste blieben danach dauerhaft null, und final liesse sich auch nachtraeglich nicht mehr
    // setzen — aus einer NotSerializableException wuerde eine dauerhafte NullPointerException
    // (Karten 915/1246). Ueber @Autowired fuellt der Kontext die Felder nach dem Aufwachen wieder.
    // Das ist die Hausregel; java:S6813 gilt hier bewusst nicht.
    @Autowired
    private transient AnforderungService anforderungService;

    private Long anforderungId;
    private Anforderung anforderung;

    /**
     * preRenderView listener (session-scoped): loads the requirement from the {@code id} viewParam on every GET.
     * viewParams are set before preRenderView, therefore {@code anforderungId} is available here. The isPostback
     * guard prevents a reload on Ajax postbacks. Replaces the former init() + @PostConstruct postConstruct().
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
