/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.DashboardTileData;
import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.dashboard.DashboardTileModelBuilder;
import ch.plaintext.boot.plugins.jsf.FacesMessages;
import ch.plaintext.boot.plugins.jsf.userprofile.UserPreferencesBackingBean;
import ch.plaintext.boot.startseite.StartseitenAnordnung;
import ch.plaintext.boot.startseite.StartseitenLayout;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Backing bean of the start page (dashboard). Builds the visible module tiles on every
 * page call - analogous to the {@link MenuBean}, which rebuilds the menu model per view.
 *
 * @author plaintext.ch
 *
 * <h2>Why field injection stays here (java:S6813, card 1273)</h2>
 *
 * <p>This bean is <b>view-scoped and serializable</b>, and with server-side state saving the JSF
 * view state is held in the session and written out with it. On a deserialization <b>no
 * constructor runs</b>: a service field set only through the
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
@Component("dashboardBean")
@Scope("view")
@Slf4j
@SuppressWarnings("java:S6813") // begruendet im Klassenkommentar oben (Karte 1273) — nicht umbauen
public class DashboardBean implements Serializable {
    private static final long serialVersionUID = 1L;

    @Autowired
    private DashboardTileModelBuilder dashboardTileModelBuilder;

    @Autowired
    private transient UserPreferencesBackingBean userPreferences;

    @Autowired
    private transient PlaintextSecurity plaintextSecurity;

    /** All permitted tiles in the user's order, hidden ones included (flag {@code hidden}). */
    private List<DashboardTileData> alle = new ArrayList<>();

    /** Is the edit mode open? */
    @Getter
    private boolean bearbeiten;

    /**
     * The arrangement as the page script writes it: {@code id:sichtbar:halb,…}
     * (see {@link StartseitenAnordnung#zuFormular}). Filled from the server when the edit mode
     * opens, so a save without any change stores exactly what is shown.
     */
    @Getter
    @Setter
    private String anordnung = "";

    @PostConstruct
    public void init() {
        laden(false);
    }

    /**
     * The tiles to render: outside the edit mode only the visible ones, inside all of them
     * (hidden ones dimmed, so they can be brought back).
     */
    public List<DashboardTileData> getTiles() {
        if (bearbeiten) {
            return alle;
        }
        List<DashboardTileData> sichtbar = new ArrayList<>();
        for (DashboardTileData tile : alle) {
            if (!tile.isHidden()) {
                sichtbar.add(tile);
            }
        }
        return sichtbar;
    }

    /** Are there permitted tiles at all? Distinguishes "no modules" from "all hidden". */
    public boolean isKachelnVorhanden() {
        return !alle.isEmpty();
    }

    /** Opens the edit mode. */
    public void anpassen() {
        bearbeiten = true;
        laden(true);
        anordnung = StartseitenAnordnung.zuFormular(alle);
    }

    /** Leaves the edit mode without storing anything. */
    public void abbrechen() {
        bearbeiten = false;
        laden(false);
    }

    /** Stores the arrangement from the hidden field and leaves the edit mode. */
    public void speichern() {
        List<String> erlaubteIds = new ArrayList<>();
        for (DashboardTileData tile : alle) {
            if (tile.getId() != null) {
                erlaubteIds.add(tile.getId());
            }
        }
        StartseitenLayout layout = StartseitenAnordnung.ausFormular(anordnung, erlaubteIds);
        if (layout != null && userPreferences != null) {
            userPreferences.merkeStartseite(schluessel(), layout);
            FacesMessages.info("Startseite gespeichert");
        }
        bearbeiten = false;
        laden(false);
    }

    /** Drops the stored arrangement: every permitted tile, default order, half width. */
    public void standardWiederherstellen() {
        if (userPreferences != null) {
            userPreferences.merkeStartseite(schluessel(), null);
        }
        FacesMessages.info("Standard wiederhergestellt");
        laden(bearbeiten);
        anordnung = StartseitenAnordnung.zuFormular(alle);
    }

    /**
     * Builds the tile list anew and applies the stored arrangement.
     *
     * @param alleAnreichern {@code true} in the edit mode: hidden tiles are shown there and need
     *                       their status too
     */
    private void laden(boolean alleAnreichern) {
        try {
            StartseitenLayout layout = gespeichertesLayout();
            Set<String> ausgeblendet = alleAnreichern ? Set.of() : StartseitenAnordnung.ausgeblendeteIds(layout);
            List<DashboardTileData> erlaubte =
                    dashboardTileModelBuilder.buildTiles(id -> !ausgeblendet.contains(id));
            alle = StartseitenAnordnung.anwenden(erlaubte, layout);
            log.debug("Dashboard initialisiert mit {} Kacheln", alle.size());
        } catch (Exception e) {
            // Defensive: a faulty tile must not shoot down the start page with a render 500
            log.error("Fehler beim Aufbau des Dashboards: {}", e.getMessage(), e);
            alle = new ArrayList<>();
        }
    }

    private StartseitenLayout gespeichertesLayout() {
        if (userPreferences == null) {
            return null;
        }
        try {
            return userPreferences.startseite(schluessel());
        } catch (Exception e) {
            // A broken preference record must not cost the start page: default arrangement.
            log.warn("Startseiten-Layout nicht lesbar, Standard wird gezeigt: {}", e.getMessage());
            return null;
        }
    }

    /** Store key: the tenant, empty without one (test, no security context). */
    String schluessel() {
        String mandat = null;
        try {
            mandat = plaintextSecurity == null ? null : plaintextSecurity.getMandat();
        } catch (Exception e) {
            log.debug("Kein Mandant fuer die Startseite: {}", e.getMessage());
        }
        return mandat == null ? "" : mandat;
    }
}
