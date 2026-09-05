/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.table;

import jakarta.faces.context.FacesContext;
import jakarta.faces.model.SelectItem;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.primefaces.component.api.UIColumn;
import org.primefaces.event.ColumnResizeEvent;
import org.primefaces.event.ToggleEvent;
import org.primefaces.model.Visibility;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Die Anzeige-Steuerung einer Tabelle: Spaltenwahl, Spaltenbreiten und benannte Profile —
 * je Benutzer gespeichert ueber einen {@link TableStateStore}.
 *
 * <p><b>Kein Spring-Bean, und das mit Absicht.</b> Jede Seite haelt sich ihr eigenes Exemplar in
 * ihrer Backing-Bean und reicht es dem Tag {@code pt:tableSettings} weiter. Ein gemeinsames Bean
 * muesste den Stand nach Seiten schluesseln und waere doch nur ein Umweg zum selben Ergebnis —
 * mit dem Zusatzrisiko, dass zwei Seiten sich gegenseitig die Spalten verstellen.</p>
 *
 * <p><b>Die Ablage kommt mit.</b> {@link UserPreferenceTableStateStore} ist ein Spring-Bean aus
 * diesem Modul und speichert je Benutzer und Mandant in {@code UserPreference}; die Backing-Bean
 * laesst sich einen {@link TableStateStore} injizieren ({@code transient}, wie ihre Services)
 * und reicht ihn an {@link #init} weiter. Mehr braucht eine Seite nicht.</p>
 *
 * <pre>{@code
 * private static final List<TableColumn> COLUMNS = List.of(
 *         new TableColumn("key",   "Schluessel", 160),
 *         new TableColumn("owner", "Owner",      180, false));
 *
 * @Getter
 * private final TableSettings anzeige = new TableSettings("projects", true);
 *
 * @Autowired
 * private transient TableStateStore tableStateStore;
 *
 * @PostConstruct
 * void init() {
 *     anzeige.init(tableStateStore, COLUMNS);
 * }
 * }</pre>
 *
 * <p>In der Seite steht dann einmal das Tag und an jeder Spalte zweimal die Abfrage:</p>
 *
 * <pre>{@code
 * <pt:tableSettings anzeige="#{projektBackingBean.anzeige}" tabelle=":fm:tbl"/>
 * ...
 * <p:column headerText="Owner"
 *           rendered="#{projektBackingBean.anzeige.isVisible('owner')}"
 *           style="#{projektBackingBean.anzeige.widthStyle('owner')}"> ... </p:column>
 * }</pre>
 *
 * <p><b>Seiten ohne feste Breiten</b> — etwa eine Uebersicht mit dreissig Auto-Spalten — schalten
 * den Breiten-Teil mit {@code mitBreiten=false} ab. Dann verschwinden die Breitenfelder aus dem
 * Bedienbereich, {@link #widthStyle(String)} liefert leer, und die Tabelle rendert im
 * Auto-Layout. Die Spaltenwahl und die Profile bleiben.</p>
 *
 * <p><b>Herkunft der Breiten-Rechnerei.</b> Sie stammt aus einer einzelnen, sehr breiten
 * Projektuebersicht und ist dort teuer erkauft worden; die Begruendungen zu Gesamtbreite,
 * Zielbreite und proportionalem Umrechnen stehen bei den jeweiligen Methoden. Wer sie liest,
 * bevor er etwas vereinfacht, spart sich den Weg ein zweites Mal.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
public class TableSettings implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Name des Profils, das angelegt wird, wenn noch keines existiert. */
    public static final String PROFILE_DEFAULT = "Standard";

    /** Schmalste Spalte in Pixeln, damit nichts unlesbar zusammenfaellt. */
    private static final int MIN_SPALTE_PX = 44;

    /** Unter dieser Gesamtbreite ist die Tabelle nicht mehr bedienbar. */
    private static final int MIN_GESAMT_PX = 200;

    /** Seitenschluessel, unter dem der Stand gespeichert wird. */
    @Getter
    private final String page;

    /** Bietet die Seite Spaltenbreiten an (resizableColumns + Breitenfelder)? */
    @Getter
    private final boolean mitBreiten;

    /**
     * Transient wie die Services in den Backing-Beans: nach einer Session-Deserialisierung bleibt
     * der Stand bedienbar, nur das Speichern setzt aus, bis die Seite neu aufgebaut wird.
     */
    private transient TableStateStore store;

    /** Die Spalten der Seite, in Tabellenreihenfolge. Bei dynamischen Spalten via {@link #setColumns} nachgefuehrt. */
    @Getter
    private List<TableColumn> columns = new ArrayList<>();

    @Getter
    private TableState state = new TableState();

    /** Im Auswahlfeld gewaehltes Profil. */
    @Getter
    @Setter
    private String selectedProfile = "";

    /** Name fuer ein neues Profil, aus dem Dialog. */
    @Getter
    @Setter
    private String newProfileName = "";

    /** Letzte Rueckmeldung an den Benutzer (Profil angelegt, Breite verteilt, ...). */
    @Getter
    private String meldung = "";

    public TableSettings(String page, boolean mitBreiten) {
        this.page = page;
        this.mitBreiten = mitBreiten;
    }

    /**
     * Laedt den gespeicherten Stand und sorgt dafuer, dass immer ein Profil aktiv ist. Von der
     * Backing-Bean im {@code @PostConstruct} aufzurufen.
     *
     * @param store   Ablage des Benutzerstands; {@code null} ist erlaubt und ergibt einen
     *                frischen Stand ohne Speicherung (Unit-Test, Vorschau)
     * @param columns die Spalten der Seite, in Tabellenreihenfolge
     */
    public void init(TableStateStore store, List<TableColumn> columns) {
        this.store = store;
        this.columns = new ArrayList<>(columns);
        this.state = store == null
                ? new TableState()
                : store.load(page, this.columns.stream().map(TableColumn::getKey).toList());
        if (this.state == null) {
            // Der Vertrag sagt "nie null"; eine fremde Umsetzung, die ihn bricht, soll die Seite
            // trotzdem nicht in eine NPE laufen lassen.
            this.state = new TableState();
        }
        ensureProfile();
    }

    /** Fuehrt die Spaltenliste nach, wenn sie sich zur Laufzeit aendert (dynamische Spalten). */
    public void setColumns(List<TableColumn> columns) {
        this.columns = new ArrayList<>(columns);
    }

    // ── Sichtbarkeit ────────────────────────────────────────────────────────

    public boolean isVisible(String key) {
        Boolean stored = state.getColumnVisible().get(key);
        if (stored != null) {
            return stored;
        }
        return columns.stream()
                .filter(c -> c.getKey().equals(key))
                .findFirst()
                .map(TableColumn::isDefaultVisible)
                .orElse(true);
    }

    public void setVisible(String key, boolean visible) {
        state.getColumnVisible().put(key, visible);
    }

    /** Sichtbare Spalten als Schluesselliste — Wert des {@code p:selectManyCheckbox}. */
    public List<String> getVisibleColumns() {
        List<String> visible = new ArrayList<>();
        for (TableColumn column : columns) {
            if (isVisible(column.getKey())) {
                visible.add(column.getKey());
            }
        }
        return visible;
    }

    public void setVisibleColumns(List<String> visible) {
        for (TableColumn column : columns) {
            state.getColumnVisible().put(column.getKey(), visible != null && visible.contains(column.getKey()));
        }
    }

    /** Auswahl fuer das {@code p:selectManyCheckbox}: Wert = Schluessel, Anzeige = Kopftext. */
    public List<SelectItem> getColumnItems() {
        return columns.stream()
                .map(c -> new SelectItem(c.getKey(), c.getLabel()))
                .map(SelectItem.class::cast)
                .toList();
    }

    /** Blendet alle Spalten ein. */
    public void alleSpaltenEin() {
        for (TableColumn column : columns) {
            state.getColumnVisible().put(column.getKey(), true);
        }
        persist();
    }

    /** Zurueck auf die Grundeinstellung der Seite. */
    public void standardSpalten() {
        for (TableColumn column : columns) {
            state.getColumnVisible().put(column.getKey(), column.isDefaultVisible());
        }
        persist();
    }

    // ── Breiten ─────────────────────────────────────────────────────────────

    /**
     * Breite einer Spalte fuer das {@code style}-Attribut, z.B. {@code "width:180px;"}.
     * Leer, wenn die Seite ohne Breiten arbeitet oder die Spalte keine Vorgabe hat.
     */
    public String widthStyle(String key) {
        if (!mitBreiten) {
            return "";
        }
        String stored = state.getColumnWidths().get(key);
        if (stored != null && !stored.isBlank()) {
            return "width:" + stored + ";";
        }
        int fallback = defaultWidth(key);
        return fallback > 0 ? "width:" + fallback + "px;" : "";
    }

    /**
     * Gesamtbreite der Tabelle als Summe der sichtbaren Spalten.
     *
     * <p>Ohne diese Angabe springt eine gezogene Spalte zurueck: bei {@code table-layout: fixed}
     * verteilt der Browser die Spalten anteilig auf die Tabellenbreite neu, sobald ihre Summe
     * nicht mehr passt. Die gesetzten Pixelwerte werden damit zu blossen Verhaeltnissen. Erst eine
     * Tabellenbreite, die der Summe entspricht, macht sie wieder verbindlich.</p>
     */
    public String getTabellenBreite() {
        if (!mitBreiten) {
            return "";
        }
        Integer gesetzt = state.getTotalWidth();
        if (gesetzt != null && gesetzt >= MIN_GESAMT_PX) {
            return "width: " + gesetzt + "px;";
        }
        return "width: " + spaltenSumme() + "px;";
    }

    /** Summe der sichtbaren Spalten, ohne eine von Hand gesetzte Gesamtbreite. */
    public int spaltenSumme() {
        int summe = 0;
        for (TableColumn column : columns) {
            if (isVisible(column.getKey())) {
                summe += breiteVon(column);
            }
        }
        return summe;
    }

    /** Uebernimmt eine gezogene Spaltenbreite; die Zuordnung laeuft ueber den Kopftext. */
    public void onColumnResize(ColumnResizeEvent event) {
        UIColumn column = event.getColumn();
        if (column == null) {
            return;
        }
        String key = keyFromHeader(column.getHeaderText());
        if (key == null) {
            log.debug("[TableSettings:{}] onColumnResize | unbekannte Spalte | header={}", page, column.getHeaderText());
            return;
        }
        state.getColumnWidths().put(key, event.getWidth() + "px");
        persist();
        log.debug("[TableSettings:{}] onColumnResize | column={} | width={}px", page, key, event.getWidth());
    }

    /**
     * Breite einer Spalte, die der Setzen-Knopf im Spaltenkopf meldet
     * (Request-Parameter {@code sp} = Kopftext, {@code px} = Breite).
     */
    public void onSpaltenbreiteGemeldet() {
        Map<String, String> params = FacesContext.getCurrentInstance()
                .getExternalContext().getRequestParameterMap();
        String key = keyFromHeader(params.get("sp"));
        Integer breite = zuBreite(params.get("px"));
        if (key == null || breite == null) {
            log.debug("[TableSettings:{}] onSpaltenbreiteGemeldet | verworfen | sp={} | px={}",
                    page, params.get("sp"), params.get("px"));
            return;
        }
        state.getColumnWidths().put(key, breite + "px");
        // Eine gesetzte Gesamtbreite waere jetzt falsch — die Tabelle folgt
        // wieder der Summe ihrer Spalten.
        state.setTotalWidth(null);
        persist();
    }

    /** Gesamtbreite fuer das Eingabefeld; ohne eigene Angabe die aktuelle Summe als Vorschlag. */
    public Integer getGesamtBreite() {
        Integer gesetzt = state.getTotalWidth();
        if (gesetzt != null && gesetzt >= MIN_GESAMT_PX) {
            return gesetzt;
        }
        return spaltenSumme();
    }

    /**
     * Zu schmale Werte werden verworfen statt beanstandet: eine Fehlermeldung an einem Feld, das
     * nur die Anzeige einstellt, haelt das ganze Formular auf — und der Benutzer wollte hier
     * nichts abschicken, sondern nur schieben.
     */
    public void setGesamtBreite(Integer breite) {
        if (breite == null || breite < MIN_GESAMT_PX) {
            state.setTotalWidth(null);
            return;
        }
        state.setTotalWidth(breite);
    }

    public void onGesamtBreiteChange() {
        persist();
    }

    /** Zielbreite fuer den Knopf im Spaltenkopf; ohne Angabe die schmalste sichtbare Spalte. */
    public Integer getZielSpaltenBreite() {
        Integer gesetzt = state.getTargetColumnWidth();
        if (gesetzt != null && gesetzt >= MIN_SPALTE_PX) {
            return gesetzt;
        }
        int schmalste = Integer.MAX_VALUE;
        for (TableColumn column : columns) {
            if (isVisible(column.getKey())) {
                schmalste = Math.min(schmalste, breiteVon(column));
            }
        }
        return schmalste == Integer.MAX_VALUE ? MIN_SPALTE_PX : schmalste;
    }

    public void setZielSpaltenBreite(Integer breite) {
        state.setTargetColumnWidth(breite == null || breite < MIN_SPALTE_PX ? null : breite);
    }

    public void onZielSpaltenBreiteChange() {
        persist();
    }

    /**
     * Weicht die eingetippte Gesamtbreite von dem ab, was die Spalten ergeben?
     * Danach richtet sich, was der Knopf daneben tut.
     */
    public boolean isBreiteAbweichend() {
        Integer gesetzt = state.getTotalWidth();
        return gesetzt != null && gesetzt >= MIN_GESAMT_PX && gesetzt != spaltenSumme();
    }

    public String getBreitenKnopfText() {
        return isBreiteAbweichend() ? "Setzen" : "Aus Spalten rechnen";
    }

    /** Ein Knopf, zwei Aufgaben — je nachdem, ob eine abweichende Breite dasteht. */
    public void breitenKnopfGedrueckt() {
        if (isBreiteAbweichend()) {
            breiteAufSpaltenVerteilen();
        } else {
            state.setTotalWidth(null);
            persist();
            meldung = "Gesamtbreite folgt wieder den Spalten.";
        }
    }

    /**
     * Rechnet alle sichtbaren Spalten proportional auf die eingetragene Gesamtbreite um; keine
     * faellt dabei unter {@value #MIN_SPALTE_PX} px.
     */
    void breiteAufSpaltenVerteilen() {
        Integer ziel = state.getTotalWidth();
        Map<String, Integer> sichtbar = new LinkedHashMap<>();
        for (TableColumn column : columns) {
            if (isVisible(column.getKey())) {
                sichtbar.put(column.getKey(), breiteVon(column));
            }
        }
        if (ziel == null || sichtbar.isEmpty()) {
            return;
        }
        int alt = sichtbar.values().stream().mapToInt(Integer::intValue).sum();
        if (alt <= 0) {
            return;
        }

        int gesetzt = 0;
        int amMinimum = 0;
        for (Map.Entry<String, Integer> e : sichtbar.entrySet()) {
            int neu = Math.round((float) e.getValue() * ziel / alt);
            if (neu < MIN_SPALTE_PX) {
                neu = MIN_SPALTE_PX;
                amMinimum++;
            }
            state.getColumnWidths().put(e.getKey(), neu + "px");
            gesetzt += neu;
        }

        if (gesetzt > ziel) {
            // Die Untergrenze hat die Rechnung gesprengt: der Wert im Feld
            // waere gelogen, also folgt die Breite wieder den Spalten.
            state.setTotalWidth(null);
            meldung = "Bei " + ziel + " px waeren " + amMinimum + " Spalten zu schmal — "
                    + "kleinstmoegliche Breite ist " + gesetzt + " px.";
        } else {
            meldung = "Spalten auf " + ziel + " px verteilt.";
        }
        persist();
    }

    /** Alle Spaltenbreiten auf die Vorgaben der Seite zuruecksetzen. */
    public void resetColumnWidths() {
        state.getColumnWidths().clear();
        state.setTotalWidth(null);
        persist();
        meldung = "Spaltenbreiten zurueckgesetzt.";
    }

    // ── Profile ─────────────────────────────────────────────────────────────

    public List<String> getProfileNames() {
        return state.getProfiles().keySet().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public boolean isProfileActive() {
        return selectedProfile != null && !selectedProfile.isBlank();
    }

    /** Auswahl im Feld: ein Profil wird sofort angewendet. */
    public void onProfileSelected() {
        if (!isProfileActive()) {
            return;
        }
        TableColumnProfile profile = state.getProfiles().get(selectedProfile);
        if (profile == null) {
            meldung = "Profil '" + selectedProfile + "' gibt es nicht mehr.";
            selectedProfile = "";
            return;
        }
        state.setColumnWidths(new LinkedHashMap<>(profile.getColumnWidths()));
        state.setColumnVisible(new LinkedHashMap<>(profile.getColumnVisible()));
        state.setTotalWidth(profile.getTotalWidth());
        state.setTargetColumnWidth(profile.getTargetColumnWidth());
        state.setActiveProfile(selectedProfile);
        save();
        meldung = "Profil '" + selectedProfile + "' angewendet.";
        log.info("[TableSettings:{}] applyProfile | name={}", page, selectedProfile);
    }

    /** Legt ein Profil unter dem im Dialog eingegebenen Namen an. */
    public void createProfile() {
        if (newProfileName == null || newProfileName.isBlank()) {
            meldung = "Bitte einen Profilnamen eingeben.";
            return;
        }
        String name = newProfileName.trim();
        if (state.getProfiles().containsKey(name)) {
            meldung = "Profil '" + name + "' gibt es schon.";
            return;
        }
        selectedProfile = name;
        state.setActiveProfile(name);
        writeActiveProfile();
        save();
        newProfileName = "";
        meldung = "Profil '" + name + "' angelegt.";
        log.info("[TableSettings:{}] createProfile | name={}", page, name);
    }

    /** Entfernt das gewaehlte Profil; der laufende Stand bleibt, wie er ist. */
    public void deleteProfile() {
        if (!isProfileActive()) {
            meldung = "Kein Profil gewaehlt.";
            return;
        }
        String name = selectedProfile;
        if (state.getProfiles().remove(name) == null) {
            meldung = "Profil '" + name + "' gibt es nicht mehr.";
            return;
        }
        if (name.equals(state.getActiveProfile())) {
            state.setActiveProfile("");
        }
        save();
        ensureProfile();
        meldung = "Profil '" + name + "' geloescht.";
        log.info("[TableSettings:{}] deleteProfile | name={}", page, name);
    }

    /**
     * Sorgt dafuer, dass immer ein Profil aktiv ist — beim ersten Aufruf entsteht aus dem
     * vorhandenen Stand ein Profil {@value #PROFILE_DEFAULT}.
     */
    private void ensureProfile() {
        if (!state.getProfiles().isEmpty()) {
            String aktiv = state.getActiveProfile();
            selectedProfile = aktiv != null && state.getProfiles().containsKey(aktiv)
                    ? aktiv
                    : getProfileNames().get(0);
            state.setActiveProfile(selectedProfile);
            return;
        }
        selectedProfile = PROFILE_DEFAULT;
        state.setActiveProfile(PROFILE_DEFAULT);
        writeActiveProfile();
        save();
    }

    /**
     * Schreibt Breiten und Sichtbarkeiten in das aktive Profil — nach jeder Aenderung, ein eigener
     * Sichern-Knopf entfaellt damit.
     */
    private void writeActiveProfile() {
        String name = state.getActiveProfile();
        if (name == null || name.isBlank()) {
            return;
        }
        TableColumnProfile profile = new TableColumnProfile();
        profile.setColumnWidths(new LinkedHashMap<>(state.getColumnWidths()));
        profile.setColumnVisible(new LinkedHashMap<>(state.getColumnVisible()));
        profile.setTotalWidth(state.getTotalWidth());
        profile.setTargetColumnWidth(state.getTargetColumnWidth());
        state.getProfiles().put(name, profile);
    }

    // ── Auf- und Zuklappen des Bereichs ─────────────────────────────────────

    public boolean isColsCollapsed() {
        return !state.isColsExpanded();
    }

    public void onColsToggle(ToggleEvent event) {
        state.setColsExpanded(event.getVisibility() == Visibility.VISIBLE);
        save();
    }

    // ── Persistenz und Zuordnung ────────────────────────────────────────────

    /** Schreibt den Stand samt aktivem Profil weg — nach jeder Spaltenaenderung aufzurufen. */
    public void persist() {
        writeActiveProfile();
        save();
    }

    private void save() {
        if (store == null) {
            // Nach einer Session-Deserialisierung fehlt die Ablage; der Stand
            // bleibt bedienbar, nur das Speichern setzt bis zum Neuaufbau aus.
            return;
        }
        store.save(page, state);
    }

    /**
     * Rechnet den Kopftext einer Spalte auf ihren Schluessel zurueck — das Resize-Ereignis liefert
     * keine eigene Spaltenkennung mit.
     */
    public String keyFromHeader(String headerText) {
        if (headerText == null) {
            return null;
        }
        String h = headerText.trim().toLowerCase(Locale.ROOT);
        for (TableColumn column : columns) {
            if (column.getLabel() != null && column.getLabel().trim().toLowerCase(Locale.ROOT).equals(h)) {
                return column.getKey();
            }
        }
        return null;
    }

    private int defaultWidth(String key) {
        return columns.stream()
                .filter(c -> c.getKey().equals(key))
                .findFirst()
                .map(TableColumn::getDefaultWidth)
                .orElse(0);
    }

    /** Gespeicherte Pixelbreite einer Spalte, sonst die Vorgabe. */
    private int breiteVon(TableColumn column) {
        String gespeichert = state.getColumnWidths().get(column.getKey());
        if (gespeichert == null || gespeichert.isBlank()) {
            return column.getDefaultWidth();
        }
        try {
            return Math.round(Float.parseFloat(gespeichert.replace("px", "").trim()));
        } catch (NumberFormatException e) {
            return column.getDefaultWidth();
        }
    }

    /** Zahl aus einem Meldeparameter, oder {@code null} wenn unbrauchbar. */
    private static Integer zuBreite(String roh) {
        if (roh == null || roh.isBlank()) {
            return null;
        }
        try {
            int wert = Math.round(Float.parseFloat(roh.trim()));
            return wert >= MIN_SPALTE_PX ? wert : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
