/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import ch.plaintext.watch.page.WatchPage;
import ch.plaintext.watch.page.WatchPageRegistry;
import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.watch.service.WatchStateService;
import jakarta.faces.context.FacesContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.List;

/**
 * Backing bean of the watch settings page.
 *
 * <p>Two things happen here: the element gallery is switched on or off for the signed-in user,
 * and the address of the watch view is shown so it can be saved to a phone's home screen.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component("watchSettingsBean")
@Scope("session")
@Slf4j
public class WatchSettingsBean implements Serializable {

    private static final long serialVersionUID = 1L;

    // Feldinjektion, nicht Konstruktor: die Bean liegt in der Session und muss serialisierbar
    // sein. Als `final transient` waeren die Dienste nach einer Deserialisierung dauerhaft null.
    @Autowired
    private transient WatchStateService zustand;

    @Autowired
    private transient WatchPageRegistry registry;

    @Getter
    @Setter
    private boolean testseiteAktiv;

    /**
     * preRenderView entry point; skips postbacks so a save does not immediately overwrite the
     * checkbox the user just ticked.
     */
    public void seitenaufruf() {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc != null && fc.isPostback()) {
            return;
        }
        testseiteAktiv = zustand.testseiteAktiv();
    }

    public void speichern() {
        zustand.setzeTestseite(testseiteAktiv);
        // Ueber FacesMessages statt FacesContext.addMessage: die zentrale Stelle ist auch
        // ausserhalb eines JSF-Requests null-sicher (Hausregel, PlaintextArchitectureTest).
        FacesMessages.info("Gespeichert",
                testseiteAktiv ? "Die Elementseite ist auf der Uhr sichtbar."
                               : "Die Elementseite ist ausgeblendet.");
    }

    /** All pages currently available, for the overview on the settings page. */
    public List<WatchPage> getSeiten() {
        return registry.verfuegbare();
    }

    /**
     * Absolute address of the watch view — the thing one actually saves to the home screen.
     * Built from the current request rather than from a configured value: behind the reverse
     * proxy the host header is what the browser used, and a property here would be one more
     * value to keep in step with the deployment.
     */
    public String getWatchAdresse() {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc == null) {
            return "/watch/home.html";
        }
        var ext = fc.getExternalContext();
        Object req = ext.getRequest();
        String basis = "";
        if (req instanceof jakarta.servlet.http.HttpServletRequest http) {
            String schema = http.getHeader("X-Forwarded-Proto") != null
                    ? http.getHeader("X-Forwarded-Proto") : http.getScheme();
            String host = http.getHeader("X-Forwarded-Host") != null
                    ? http.getHeader("X-Forwarded-Host") : http.getHeader("Host");
            if (host != null) {
                basis = schema + "://" + host;
            }
        }
        return basis + ext.getRequestContextPath() + "/watch/home.html";
    }

}
