/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch.web;

import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.watch.page.WatchPage;
import ch.plaintext.watch.page.WatchPageRegistry;
import ch.plaintext.watch.service.WatchHandyLinkService;
import ch.plaintext.watch.service.WatchStateService;
import jakarta.faces.context.FacesContext;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Backing bean of the watch settings page.
 *
 * <p>Three things happen here: the pages are switched on and off for the signed-in user, the
 * personal phone link is issued, shown and switched off, and the plain address of the watch is
 * shown for whoever wants to save it to a home screen after a normal sign-in.</p>
 *
 * <p>The switches are the <b>same</b> selection the watch overview operates (card 1260) — one
 * mechanism, two places to reach it.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Component("watchSettingsBean")
@Scope("session")
@Slf4j
public class WatchSettingsBean implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter WANN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    // Feldinjektion, nicht Konstruktor: die Bean liegt in der Session und muss serialisierbar
    // sein. Als `final transient` waeren die Dienste nach einer Deserialisierung dauerhaft null.
    @Autowired
    private transient WatchStateService zustand;

    @Autowired
    private transient WatchPageRegistry registry;

    @Autowired
    private transient WatchHandyLinkService handyLink;

    /**
     * The link, held for exactly as long as the page is open after generating it.
     *
     * <p>It is <b>not</b> persisted — see {@code WatchUserState.getHandyLinkJti()}. A bearer
     * credential in a column would be in every module export; here it lives in one session and
     * is gone with it.</p>
     */
    @Getter
    private String neuerLink;

    @Getter
    @Setter
    private List<SeitenSchalter> schalter = new ArrayList<>();

    /**
     * preRenderView entry point; skips postbacks so a save does not immediately overwrite the
     * boxes the user just ticked.
     */
    public void seitenaufruf() {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc != null && fc.isPostback()) {
            return;
        }
        ladeSchalter();
    }

    private void ladeSchalter() {
        List<SeitenSchalter> neu = new ArrayList<>();
        for (WatchPage s : registry.alle()) {
            // Was die Rollen ohnehin verbieten, wird nicht als Schalter angeboten: ein Haken,
            // der nichts bewirkt, ist schlimmer als kein Haken.
            if (!sicherVerfuegbar(s)) {
                continue;
            }
            // Die Uebersicht selbst ist nicht abschaltbar — sie ist der Weg zu den Schaltern.
            if (!s.imUmlauf()) {
                continue;
            }
            neu.add(new SeitenSchalter(s.id(), s.title(), s.order(), zustand.seiteAktiv(s.id())));
        }
        this.schalter = neu;
    }

    private boolean sicherVerfuegbar(WatchPage s) {
        try {
            return s.available();
        } catch (Exception e) {
            log.warn("Watch: Zugriffsregel von {} nicht auswertbar: {}", s.id(), e.toString());
            return false;
        }
    }

    public void speichern() {
        for (SeitenSchalter s : schalter) {
            zustand.setzeSeite(s.getId(), s.isAktiv());
        }
        long an = schalter.stream().filter(SeitenSchalter::isAktiv).count();
        // Ueber FacesMessages statt FacesContext.addMessage: die zentrale Stelle ist auch
        // ausserhalb eines JSF-Requests null-sicher (Hausregel, PlaintextArchitectureTest).
        FacesMessages.info("Gespeichert", an + " von " + schalter.size() + " Seiten sind sichtbar.");
    }

    // ---- Handy-Link ---------------------------------------------------------------------------

    /** Whether this installation can issue a phone link at all (token module present). */
    public boolean isHandyLinkMoeglich() {
        return handyLink.verfuegbar();
    }

    public boolean isHandyLinkAktiv() {
        return zustand.handyLinkAktiv();
    }

    /** When the currently valid link was issued, formatted — empty when there is none. */
    public String getHandyLinkErstellt() {
        return zustand.handyLinkErstellt().map(WANN::format).orElse("");
    }

    /**
     * Issues a link and shows it once.
     *
     * <p>Generating revokes the previous one first, so the old phone stops working on its next
     * tap — that is what the message says, and it is literally true because
     * {@code WatchTokenSitzungFilter} re-checks the token on every request.</p>
     */
    public void erzeugeHandyLink() {
        try {
            neuerLink = handyLink.erzeuge();
            FacesMessages.info("Handy-Link erstellt",
                    "Jetzt speichern — er wird nicht noch einmal angezeigt. Ein vorher "
                            + "ausgegebener Link ist ab sofort ungueltig.");
        } catch (RuntimeException e) {
            neuerLink = null;
            log.warn("Watch: Handy-Link nicht erstellbar: {}", e.toString());
            FacesMessages.warn("Handy-Link nicht erstellt", e.getMessage());
        }
    }

    public void schalteHandyLinkAb() {
        neuerLink = null;
        int widerrufen = handyLink.widerrufe();
        FacesMessages.info("Handy-Link abgeschaltet",
                widerrufen + " Token widerrufen. Ein offener Zugang endet beim naechsten Tippen.");
    }

    // ---- Adresse ------------------------------------------------------------------------------

    /**
     * Absolute address of the watch view — the thing one actually saves to the home screen after
     * a normal sign-in. Built from the current request rather than from a configured value:
     * behind the reverse proxy the host header is what the browser used, and a property here
     * would be one more value to keep in step with the deployment.
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

    /** One row of the switch list. */
    public static class SeitenSchalter implements Serializable {

        private static final long serialVersionUID = 1L;

        @Getter
        private final String id;
        @Getter
        private final String titel;
        @Getter
        private final int reihenfolge;
        @Getter
        @Setter
        private boolean aktiv;

        public SeitenSchalter(String id, String titel, int reihenfolge, boolean aktiv) {
            this.id = id;
            this.titel = titel;
            this.reihenfolge = reihenfolge;
            this.aktiv = aktiv;
        }
    }
}
