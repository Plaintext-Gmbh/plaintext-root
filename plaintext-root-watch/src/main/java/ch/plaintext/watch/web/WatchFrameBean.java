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

/**
 * Drives the page stack of the watch view: which page is shown, and moving between them.
 *
 * <p>Session scoped because the watch view is opened repeatedly from a stored link; the
 * position itself lives in the database ({@link WatchStateService}), the bean only holds it
 * for the current render.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component("watchFrameBean")
@Scope("session")
@Slf4j
public class WatchFrameBean implements Serializable {

    private static final long serialVersionUID = 1L;

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
     */
    public void seitenaufruf() {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc != null && fc.isPostback() && aktuelle != null) {
            return;
        }
        aktuelle = zustand.aktuelleSeite().orElse(null);
        if (aktuelle != null) {
            zustand.merkeSeite(aktuelle.id());
        }
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

    /** All pages the user may see — for the position indicator in the header. */
    public List<WatchPage> getSeiten() {
        return registry.verfuegbare();
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
}
