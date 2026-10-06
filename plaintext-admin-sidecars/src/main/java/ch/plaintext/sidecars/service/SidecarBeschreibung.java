/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.sidecars.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Die Antwort von {@code GET /.well-known/plaintext-sidecar}, geprüft nach
 * {@code docs/SIDECAR_PROTOKOLL.md} (Karte 1400).
 *
 * <p>Streng bei dem, was die Registry für Entscheidungen braucht (Protokoll, Name, Status, Pfade),
 * nachsichtig beim Rest: eine fehlerhafte Fähigkeit wird übergangen und als Hinweis gemeldet, statt
 * den ganzen Dienst unsichtbar zu machen.</p>
 */
public record SidecarBeschreibung(String protokoll, String name, String titel, String beschreibung, String version,
                                  String status, String statusText, String authArt, List<Teil> teile,
                                  List<Faehigkeit> faehigkeiten, String doku, List<String> hinweise) {

    /** Anfang der Hinweise zu einer Faehigkeit (Karte 1416, Sonar java:S1192). */
    private static final String FAEHIGKEIT = "Fähigkeit ";

    /** JSON-Feld (Karte 1416, Sonar java:S1192). */
    private static final String FELD_TITEL = "titel";

    public static final String PROTOKOLL_VERSION = "plaintext-sidecar/1";
    static final Pattern NAME_MUSTER = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
    /** Ein Teil einer Faehigkeits-Id; die Id selbst sind mindestens zwei solche Teile mit Punkt (siehe istFaehigkeitId). */
    private static final Pattern ID_TEIL = Pattern.compile("[a-z0-9][a-z0-9-]*");
    static final Set<String> STATUS_WERTE = Set.of("ok", "eingeschraenkt", "fehler");
    static final Set<String> METHODEN = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    static final Set<String> SEITENEFFEKTE = Set.of("keiner", "intern", "aussen");
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Ein Unterzustand, z. B. «WhatsApp gekoppelt». */
    public record Teil(String name, String status, String text) {
    }

    /**
     * Eine Fähigkeit des Sidecars.
     *
     * @param eingabe JSON-Schema bzw. {@code {"inhalt": …, "parameter": …}} oder {@code null}
     * @param ausgabe dito
     */
    public record Faehigkeit(String id, String titel, String beschreibung, String methode, String pfad,
                             JsonNode eingabe, JsonNode ausgabe, boolean auth, boolean mcp, String seiteneffekt) {

        /** @return ob ein LLM die Fähigkeit über das allgemeine MCP-Werkzeug auslösen darf */
        public boolean perMcpAufrufbar() {
            return mcp && !"aussen".equals(seiteneffekt);
        }
    }

    /** Die Antwort ist kein gültiges Protokoll-Dokument. */
    public static class Ungueltig extends Exception {
        public Ungueltig(String meldung) {
            super(meldung);
        }
    }

    /** @return {@code true} bei {@code auth.art = keine} */
    public boolean ohneAuth() {
        return "keine".equals(authArt);
    }

    /** @return die Fähigkeit mit dieser Id oder {@code null} */
    public Faehigkeit faehigkeit(String id) {
        return faehigkeiten.stream().filter(f -> f.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * @param json Rohtext der Antwort
     * @throws Ungueltig wenn Pflichtfelder fehlen oder das Protokoll unbekannt ist
     */
    public static SidecarBeschreibung lies(String json) throws Ungueltig {
        JsonNode n;
        try {
            n = JSON.readTree(json);
        } catch (IOException _) {
            throw new Ungueltig("Antwort ist kein JSON.");
        }
        if (n == null || !n.isObject()) {
            throw new Ungueltig("Antwort ist kein JSON-Objekt.");
        }
        String protokoll = text(n, "protokoll");
        String name = text(n, "name");
        String status = text(n, "status");
        String authArt = n.path("auth").path("art").asText(null);
        pruefeKopf(protokoll, name, status, authArt);
        List<String> hinweise = new ArrayList<>();
        List<Teil> teile = teile(n);
        List<Faehigkeit> faehigkeiten = new ArrayList<>();
        for (JsonNode f : n.path("faehigkeiten")) {
            String fehler = pruefeFaehigkeit(f);
            if (fehler == null) {
                faehigkeiten.add(faehigkeit(f));
            } else {
                hinweise.add(fehler);
            }
        }
        return new SidecarBeschreibung(protokoll, name, kurz(text(n, FELD_TITEL)), text(n, "beschreibung"), kurz(text(n, "version")),
                status, kurz(text(n, "statusText")), authArt, List.copyOf(teile), List.copyOf(faehigkeiten),
                kurz(text(n, "doku")), List.copyOf(hinweise));
    }

    /** Pflichtfelder des Kopfs, in dieser Reihenfolge (Karte 1416, Sonar java:S3776: aus lies() herausgeloest). */
    private static void pruefeKopf(String protokoll, String name, String status, String authArt) throws Ungueltig {
        if (!PROTOKOLL_VERSION.equals(protokoll)) {
            throw new Ungueltig(protokoll == null ? "Feld «protokoll» fehlt." : "Unbekanntes Protokoll «" + kurz(protokoll) + "».");
        }
        if (name == null || !NAME_MUSTER.matcher(name).matches()) {
            throw new Ungueltig("Feld «name» fehlt oder ist ungültig (erlaubt: a-z, 0-9, -, höchstens 40 Zeichen).");
        }
        if (status == null || !STATUS_WERTE.contains(status)) {
            throw new Ungueltig("Feld «status» muss ok, eingeschraenkt oder fehler sein.");
        }
        if (!"bearer".equals(authArt) && !"keine".equals(authArt)) {
            throw new Ungueltig("Feld «auth.art» muss bearer oder keine sein.");
        }
    }

    private static List<Teil> teile(JsonNode n) {
        List<Teil> teile = new ArrayList<>();
        for (JsonNode t : n.path("teile")) {
            if (text(t, "name") != null) {
                teile.add(new Teil(kurz(text(t, "name")), kurz(text(t, "status")), kurz(text(t, "text"))));
            }
        }
        return teile;
    }

    /** Eine schon gepruefte Faehigkeit (pruefeFaehigkeit gab null). */
    private static Faehigkeit faehigkeit(JsonNode f) {
        return new Faehigkeit(text(f, "id"), kurz(text(f, FELD_TITEL)), text(f, "beschreibung"),
                grossOderNull(text(f, "methode")), text(f, "pfad"),
                f.has("eingabe") ? f.get("eingabe") : null, f.has("ausgabe") ? f.get("ausgabe") : null,
                f.path("auth").asBoolean(true), f.path("mcp").asBoolean(false), text(f, "seiteneffekt"));
    }

    /** @return Fehlermeldung oder {@code null}, wenn die Fähigkeit brauchbar ist */
    static String pruefeFaehigkeit(JsonNode f) {
        String id = text(f, "id");
        if (!istFaehigkeitId(id)) {
            return "Fähigkeit ohne gültige id («" + kurz(id) + "») übergangen.";
        }
        String methode = text(f, "methode");
        if (methode == null || !METHODEN.contains(methode.toUpperCase(Locale.ROOT))) {
            return FAEHIGKEIT + id + ": ungültige methode.";
        }
        if (!pfadGueltig(text(f, "pfad"))) {
            return FAEHIGKEIT + id + ": ungültiger pfad.";
        }
        String s = text(f, "seiteneffekt");
        if (s == null || !SEITENEFFEKTE.contains(s)) {
            return FAEHIGKEIT + id + ": seiteneffekt muss keiner, intern oder aussen sein.";
        }
        if (text(f, FELD_TITEL) == null) {
            return FAEHIGKEIT + id + ": titel fehlt.";
        }
        return null;
    }

    /**
     * {@code bereich.aktion} mit mindestens zwei Teilen aus a-z, 0-9 und Bindestrich, hoechstens 100 Zeichen.
     * Zerlegt am Punkt statt einer wiederholten Gruppe im Ausdruck (Karte 1416, Sonar java:S5998); die
     * Laenge wird vor jedem Ausdruck geprueft.
     */
    static boolean istFaehigkeitId(String id) {
        if (id == null || id.length() > 100) {
            return false;
        }
        String[] teile = id.split("\\.", -1);
        if (teile.length < 2) {
            return false;
        }
        for (String t : teile) {
            if (!ID_TEIL.matcher(t).matches()) {
                return false;
            }
        }
        return true;
    }

    /** Relativer Pfad ohne Ausbruch: kein {@code ..}, kein Schema, keine Query, kein Backslash. */
    static boolean pfadGueltig(String pfad) {
        return pfad != null && pfad.startsWith("/") && !pfad.startsWith("//") && pfad.length() <= 200
                && !pfad.contains("..") && !pfad.contains("\\") && !pfad.contains("://")
                && !pfad.contains("?") && !pfad.contains("#") && !pfad.contains("%")
                && pfad.chars().noneMatch(c -> c < 0x21 || c > 0x7e);
    }

    private static String text(JsonNode n, String feld) {
        JsonNode v = n.get(feld);
        return v == null || v.isNull() || !v.isValueNode() ? null : v.asText();
    }

    /** pruefeFaehigkeit verlangt die Methode schon; hier nur, damit kein Weg ueber null fuehrt. */
    private static String grossOderNull(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static String kurz(String s) {
        if (s == null || s.length() <= 200) {
            return s;
        }
        return s.substring(0, 200) + "…";
    }
}
