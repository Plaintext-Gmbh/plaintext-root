/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import ch.plaintext.watch.page.WatchPage;
import ch.plaintext.watch.page.WatchPageRegistry;
import ch.plaintext.watch.service.WatchStateService;
import jakarta.faces.context.FacesContext;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * Drives the page stack of the watch view: which page is shown, and moving between them.
 *
 * <p>Session scoped because the watch view is opened repeatedly from a stored link; the
 * position itself lives in the database ({@link WatchStateService}), the bean only holds it
 * for the current render.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
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
@Component("watchFrameBean")
@Scope("session")
@Slf4j
@SuppressWarnings("java:S6813") // begruendet im Klassenkommentar oben (Karte 1273) — nicht umbauen
public class WatchFrameBean implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * The key PrimeFaces uses for the CSP nonce — in the view map when rendering and as the
     * request parameter on the way back ({@code org.primefaces.csp.CspState#getNonce}).
     */
    static final String NONCE_SCHLUESSEL = "primefaces.nonce";

    // Feldinjektion, nicht Konstruktorinjektion: die Bean liegt in der Session und muss
    // serialisierbar sein, die Dienste sind es nicht. Als `final transient` waeren sie nach
    // einer Deserialisierung dauerhaft null — kein Konstruktor laeuft mehr, und setzen kann
    // sie dann niemand. Spring spritzt die Felder dagegen neu ein. Gleiches Muster wie
    // ApiTokenBackingBean in plaintext-admin-apitoken.
    @Autowired
    private transient WatchPageRegistry registry;

    @Autowired
    private transient WatchStateService zustand;

    @Getter
    private transient WatchPage aktuelle;

    /**
     * preRenderView entry point. Loads the remembered page on a real page load and skips
     * postbacks — otherwise every button press would re-read and re-write the position.
     *
     * <p>It also guards the page that is actually being rendered. {@code available()} used to
     * steer only the navigation; a page switched off was still reachable by typing its address.
     * Measured on PROD: {@code /watch/elemente.html} answered 200 with the full gallery while
     * the user's setting had it off. "Switched off" has to mean "not there", otherwise the
     * setting promises something it does not keep.</p>
     */
    public void seitenaufruf() {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc != null && fc.isPostback() && aktuelle != null) {
            return;
        }
        // Die Aufloesung id -> Seite liegt hier und nicht im Zustandsdienst: der Dienst darf das
        // Seitenregister nicht kennen, sonst schliesst sich der Kreis ueber WatchTestPage, die
        // ihn selbst befragt (Spring: BeanCurrentlyInCreationException).
        WatchPage angefragt = angefragteSeite(fc).orElse(null);
        if (angefragt != null) {
            // Direkt aufgerufen: nur zeigen, wenn die Seite wirklich sichtbar ist. Bewusst
            // registry.sichtbar(...) und nicht angefragt.available(): available() beantwortet seit
            // Karte 1257 nur noch die Rollenfrage, die Auswahl des Benutzers liegt daneben. Wer
            // hier nur available() fraegt, laesst eine abgeschaltete Seite per Adresszeile wieder
            // herein — genau die Luecke, die PR #219 fuer die Testseite geschlossen hat.
            // imUmlauf() wird hier NICHT geprueft: eine Seite ausserhalb des Umlaufs (die
            // Uebersicht) ist ueber ihre Adresse gerade erwuenscht.
            if (registry.sichtbar(angefragt)) {
                aktuelle = angefragt;
                zustand.merkeSeite(aktuelle.id());
                return;
            }
            log.info("Watch: Seite {} ist abgeschaltet, leite auf die erste um", angefragt.id());
            wechsle(registry.erste().orElse(null));
            return;
        }
        // Dieselbe Regel, die auch WatchStartController anwendet — seit Karte 1289 steht sie
        // einmal in der Registry statt zweimal hier und dort.
        aktuelle = registry.einstieg(zustand.gemerkteSeitenId().orElse(null)).orElse(null);
        if (aktuelle != null) {
            zustand.merkeSeite(aktuelle.id());
        }
    }

    /**
     * Which registered page the current request is rendering, if any.
     *
     * <p>Matched on the view id rather than on a parameter: the address is what the browser
     * really asked for, and there is nothing a caller could set to name a different page.</p>
     */
    private java.util.Optional<WatchPage> angefragteSeite(FacesContext fc) {
        if (fc == null || fc.getViewRoot() == null) {
            return java.util.Optional.empty();
        }
        String view = fc.getViewRoot().getViewId();
        if (view == null) {
            return java.util.Optional.empty();
        }
        return registry.alle().stream().filter(p -> view.equals(p.view())).findFirst();
    }

    public void weiter() {
        wechsle(registry.naechste(aktuelle == null ? null : aktuelle.id()).orElse(null));
    }

    public void zurueck() {
        wechsle(registry.vorherige(aktuelle == null ? null : aktuelle.id()).orElse(null));
    }

    private void wechsle(WatchPage ziel) {
        if (ziel == null) {
            return;
        }
        aktuelle = ziel;
        zustand.merkeSeite(ziel.id());
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc == null) {
            return;
        }
        try {
            // Redirect statt Forward: die Adresse im Browser soll die gezeigte Seite nennen,
            // damit ein gespeicherter Link (Home-Bildschirm) dort wieder landet.
            String basis = fc.getExternalContext().getRequestContextPath();
            fc.getExternalContext().redirect(basis + ziel.view().replace(".xhtml", ".html"));
        } catch (IOException e) {
            log.warn("Watch: Wechsel auf {} fehlgeschlagen: {}", ziel.id(), e.toString());
        }
    }

    /** The pages of the rotation — for the position indicator in the header. */
    public List<WatchPage> getSeiten() {
        return registry.verfuegbare();
    }

    /**
     * Address of the overview with the page switches, or empty when there is none.
     *
     * <p>The overview sits outside the rotation ({@link WatchPage#imUmlauf()}), so next/previous
     * never reaches it — without this link it would only be reachable by typing the address.</p>
     */
    public String getUebersichtAdresse() {
        return registry.alle().stream()
                .filter(p -> !p.imUmlauf())
                .filter(registry::sichtbar)
                .findFirst()
                .map(p -> p.view().replace(".xhtml", ".html"))
                .orElse("");
    }

    /** Human-readable position, e.g. "2/4". Empty when there is nothing to show. */
    public String getPosition() {
        List<WatchPage> s = getSeiten();
        if (aktuelle == null || s.isEmpty()) {
            return "";
        }
        int i = 0;
        for (int k = 0; k < s.size(); k++) {
            if (s.get(k).id().equals(aktuelle.id())) {
                i = k + 1;
                break;
            }
        }
        return i + "/" + s.size();
    }

    public String getTitel() {
        return aktuelle == null ? "Watch" : aktuelle.title();
    }

    /**
     * The CSP nonce of this view, for the hidden field every watch form has to carry.
     *
     * <h2>Why a page without PrimeFaces needs a PrimeFaces value</h2>
     *
     * <p>With {@code joinfaces.primefaces.csp=true} PrimeFaces compares, on every postback, the
     * nonce stored in the view map against the request parameter of the same name and throws
     * {@code CSP nonce mismatch} when they differ — a whitelabel error page, no message the user
     * could act on. On a normal page PrimeFaces' own JavaScript appends that parameter. The watch
     * pages deliberately carry no PrimeFaces (card 1247: one {@code p:} tag measured 1 055 KB
     * against 56 KB), so nothing appends it and every button press failed (card 1256, reported
     * from a phone on 19.09.2026, twice in the log).</p>
     *
     * <p>The value is read straight out of the view map, not through the PrimeFaces API: that
     * keeps this module free of a PrimeFaces dependency, which is the whole point of it. The key
     * is the same string PrimeFaces reads on the way back, so the two cannot drift apart
     * silently — if PrimeFaces ever renames it, the field goes empty and
     * {@code WatchNonceFeldVertragTest} in plaintext-app fails, rather than the pages breaking in
     * production.</p>
     *
     * @return the nonce, or an empty string when there is none (then the field is inert)
     */
    public String getCspNonce() {
        try {
            FacesContext fc = FacesContext.getCurrentInstance();
            if (fc == null || fc.getViewRoot() == null) {
                return "";
            }
            Map<String, Object> viewMap = fc.getViewRoot().getViewMap(false);
            if (viewMap == null) {
                return "";
            }
            Object nonce = viewMap.get(NONCE_SCHLUESSEL);
            return nonce == null ? "" : nonce.toString();
        } catch (Exception e) {
            log.warn("Watch: CSP-Nonce nicht lesbar: {}", e.toString());
            return "";
        }
    }
}
