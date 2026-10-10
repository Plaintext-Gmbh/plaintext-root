/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.ablage;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.ablagen.AblageEintrag;
import ch.plaintext.ablagen.DateiAblage;
import ch.plaintext.ablagen.DateiAblagenRegister;
import jakarta.faces.component.UIComponent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.primefaces.event.FileUploadEvent;
import org.primefaces.model.file.UploadedFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Karte 1440: {@link AblageAuswahl} prüft selbst, das Tag blendet nur aus. Deshalb hier ohne JSF:
 * Pfad-Traversal, Rechte, Dateityp und Grösse gegen eine Ablage im Speicher, die jeden Pfad mitschreibt.
 */
class AblageAuswahlTest {

    /** Ablage im Speicher; merkt sich jeden angefragten Pfad. */
    static final class Speicher implements DateiAblage {
        final String name;
        final Map<String, byte[]> dateien = new TreeMap<>();
        final List<String> zugriffe = new ArrayList<>();

        Speicher(String name) {
            this.name = name;
        }

        @Override public String name() { return name; }
        @Override public void schreibe(String pfad, byte[] daten, String typ) { zugriffe.add(pfad); dateien.put(pfad, daten); }
        @Override public byte[] lies(String pfad) throws IOException {
            zugriffe.add(pfad);
            byte[] b = dateien.get(pfad);
            if (b == null) throw new IOException("fehlt: " + pfad);
            return b;
        }
        @Override public boolean existiert(String pfad) { return dateien.containsKey(pfad); }
        @Override public List<AblageEintrag> liste(String ordner) {
            zugriffe.add(ordner);
            String p = ordner.isEmpty() ? "" : ordner + "/";
            Map<String, AblageEintrag> l = new TreeMap<>();
            dateien.forEach((k, v) -> {
                if (!k.startsWith(p)) return;
                String rest = k.substring(p.length());
                int i = rest.indexOf('/');
                l.put(i < 0 ? rest : rest.substring(0, i), i < 0 ? new AblageEintrag(k, false, v.length, null)
                        : new AblageEintrag(p + rest.substring(0, i), true, -1, null));
            });
            return new ArrayList<>(l.values());
        }
        @Override public void loesche(String pfad) { dateien.remove(pfad); }
        @Override public void legeOrdnerAn(String pfad) { dateien.put(pfad + "/.keep", new byte[0]); }
        @Override public void verschiebe(String von, String nach) throws IOException {
            List<String> weg = dateien.keySet().stream().filter(k -> k.equals(von) || k.startsWith(von + "/")).toList();
            if (weg.isEmpty() || dateien.keySet().stream().anyMatch(k -> k.equals(nach) || k.startsWith(nach + "/"))) {
                throw new IOException("verschieben: " + von + " -> " + nach);
            }
            weg.forEach(k -> dateien.put(nach + k.substring(von.length()), dateien.remove(k)));
        }
        @Override public void loescheOrdner(String pfad, boolean rekursiv) throws IOException {
            List<String> drin = dateien.keySet().stream().filter(k -> k.startsWith(pfad + "/")).toList();
            if (drin.isEmpty()) throw new IOException("kein Ordner: " + pfad);
            if (!rekursiv && drin.stream().anyMatch(k -> !k.equals(pfad + "/.keep"))) throw new java.nio.file.DirectoryNotEmptyException(pfad);
            drin.forEach(dateien::remove);
        }
    }

    private final Speicher offen = new Speicher("offen");
    private final Speicher geheim = new Speicher("geheim");
    private final Set<String> rollen = new HashSet<>();
    private PlaintextSecurity security;
    private DateiAblagenRegister register;

    private static final AblageEinsatz EINSATZ = new AblageEinsatz(List.of(
            new AblageEinsatz.Freigabe("offen", Set.of("USER"), Set.of("ADMIN")),
            new AblageEinsatz.Freigabe("geheim", Set.of(), Set.of("ROOT"))),
            "wurzel/m1", Set.of("txt", "drawio"), 100);

    @BeforeEach
    void setUp() {
        security = mock(PlaintextSecurity.class);
        when(security.ifGranted(anyString())).thenAnswer(a -> rollen.contains(a.<String>getArgument(0)));
        register = mock(DateiAblagenRegister.class);
        when(register.namen()).thenReturn(List.of("geheim", "offen"));
        when(register.ablage("offen")).thenReturn(Optional.of(offen));
        when(register.ablage("geheim")).thenReturn(Optional.of(geheim));
        offen.dateien.put("wurzel/m1/a.txt", "hallo".getBytes(StandardCharsets.UTF_8));
        offen.dateien.put("wurzel/m1/b.exe", new byte[]{1});
        offen.dateien.put("wurzel/m1/unter/c.drawio", "<mxfile/>".getBytes(StandardCharsets.UTF_8));
        offen.dateien.put("wurzel/m1/gross.txt", new byte[101]);
        offen.dateien.put("wurzel/m2/fremd.txt", "anderes Mandat".getBytes(StandardCharsets.UTF_8));
        offen.dateien.put("ausserhalb.txt", "x".getBytes(StandardCharsets.UTF_8));
    }

    private AblageAuswahl auswahl(String... r) {
        rollen.addAll(List.of(r));
        AblageAuswahl a = new AblageAuswahl(EINSATZ);
        a.init(register, security);
        return a;
    }

    // ---------- Rechte ----------

    @Test
    @DisplayName("Rechte: USER sieht nur «offen», wählt sie vor und darf dort nicht schreiben")
    void userSiehtNurOffen() throws IOException {
        AblageAuswahl a = auswahl("USER");
        assertThat(a.getAblagen()).containsExactly("offen");
        assertThat(a.getAblage()).isEqualTo("offen");
        assertThat(a.isSchreibbar()).isFalse();
        assertThat(a.lies("a.txt")).asString(StandardCharsets.UTF_8).isEqualTo("hallo");
        assertThatThrownBy(() -> a.speichere("neu.txt", new byte[1])).isInstanceOf(SecurityException.class);
        assertThat(offen.dateien).doesNotContainKey("wurzel/m1/neu.txt");
    }

    @Test
    @DisplayName("Rechte: eine Ablage ohne Recht lässt sich auch nicht direkt setzen (Negativ)")
    void ablageOhneRechtNichtWaehlbar() {
        AblageAuswahl a = auswahl("USER");
        a.setAblage("geheim");
        assertThat(a.getAblage()).isNull();
        assertThat(geheim.zugriffe).isEmpty();
    }

    @Test
    @DisplayName("Rechte: Schreibrecht allein genügt zum Lesen; ROOT sieht «geheim», nicht aber «offen»")
    void schreibrechtSchliesstLesenEin() {
        AblageAuswahl a = auswahl("ROOT");
        assertThat(a.getAblagen()).containsExactly("geheim");
        assertThat(a.isSchreibbar()).isTrue();
    }

    @Test
    @DisplayName("Rechte: ADMIN schreibt in «offen» (Positiv), die Datei liegt unter der Wurzel")
    void adminSchreibt() throws IOException {
        AblageAuswahl a = auswahl("ADMIN");
        a.speichere("neu.txt", "x".getBytes(StandardCharsets.UTF_8));
        assertThat(offen.dateien).containsKey("wurzel/m1/neu.txt");
        assertThat(a.getEintraege()).extracting(AblageAuswahl.Eintrag::name).contains("neu.txt");
    }

    @Test
    @DisplayName("Rechte: ein entzogenes Recht wirkt beim nächsten Zugriff, nicht erst bei der nächsten Sitzung")
    void entzogenesRechtWirktSofort() {
        AblageAuswahl a = auswahl("USER");
        rollen.clear();
        assertThatThrownBy(() -> a.lies("a.txt")).isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Rechte: ohne Register (Anwendung ohne Speicher-Ablagen) gibt es keine Auswahl")
    void ohneRegister() {
        AblageAuswahl a = new AblageAuswahl(EINSATZ);
        a.init(null, security);
        assertThat(a.getAblagen()).isEmpty();
        assertThat(a.getAblage()).isNull();
    }

    // ---------- Pfad-Traversal ----------

    @ParameterizedTest
    @ValueSource(strings = {"..", ".", "../m2/fremd.txt", "../../ausserhalb.txt", "unter/c.drawio", "..\\m2\\fremd.txt",
            "/etc/passwd.txt", "", " a.txt", "a.txt\n", "a\u0000.txt"})
    @DisplayName("Traversal: Namen mit Pfadteilen werden abgelehnt, bevor die Ablage sie sieht")
    void traversalAbgelehnt(String name) {
        AblageAuswahl a = auswahl("ADMIN");
        offen.zugriffe.clear();
        assertThatThrownBy(() -> a.lies(name)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.speichere(name, new byte[1])).isInstanceOf(IllegalArgumentException.class);
        a.oeffneOrdner(name);
        assertThat(a.getOrdner()).isEmpty();
        assertThat(offen.zugriffe).isEmpty();
    }

    @Test
    @DisplayName("Traversal: «Hoch» endet an der Wurzel, jeder Zugriff bleibt darunter")
    void hochEndetAnDerWurzel() throws IOException {
        AblageAuswahl a = auswahl("ADMIN");
        a.oeffneOrdner("unter");
        assertThat(a.getEintraege()).extracting(AblageAuswahl.Eintrag::name).containsExactly("c.drawio");
        assertThat(a.lies("c.drawio")).isNotEmpty();
        a.hoch();
        a.hoch();
        a.hoch();
        assertThat(a.isObersterOrdner()).isTrue();
        assertThat(a.getEintraege()).extracting(AblageAuswahl.Eintrag::name).containsExactly("unter", "a.txt", "gross.txt");
        assertThat(offen.zugriffe).allMatch(p -> p.equals("wurzel/m1") || p.startsWith("wurzel/m1/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../x", "a/../b", "/abs", "a//b", "a/./b"})
    @DisplayName("Traversal: eine Wurzel mit .., absolut oder leeren Teilen lässt sich nicht konfigurieren")
    void wurzelGeprueft(String wurzel) {
        assertThatThrownBy(() -> new AblageEinsatz(List.of(), wurzel, Set.of(), 1)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- Dateityp ----------

    @Test
    @DisplayName("Typ: nicht erlaubte Endungen sind unsichtbar, nicht lesbar und nicht speicherbar")
    void typAllowlist() {
        AblageAuswahl a = auswahl("ADMIN");
        assertThat(a.getEintraege()).extracting(AblageAuswahl.Eintrag::name).doesNotContain("b.exe");
        assertThatThrownBy(() -> a.lies("b.exe")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.speichere("x.html", new byte[1])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.speichere("ohne-endung", new byte[1])).isInstanceOf(IllegalArgumentException.class);
        assertThat(a.getAllowTypes()).isEqualTo("/\\.(drawio|txt)$/i");
    }

    @Test
    @DisplayName("Typ: Grossschreibung der Endung spielt keine Rolle")
    void typOhneGrossKlein() throws IOException {
        AblageAuswahl a = auswahl("ADMIN");
        a.speichere("NEU.TXT", new byte[1]);
        assertThat(offen.dateien).containsKey("wurzel/m1/NEU.TXT");
    }

    @Test
    @DisplayName("Typ: wer Schreiben erlaubt, muss die Typen nennen")
    void schreibenOhneTypen() {
        List<AblageEinsatz.Freigabe> f = List.of(new AblageEinsatz.Freigabe("*", Set.of(), Set.of("ADMIN")));
        assertThatThrownBy(() -> new AblageEinsatz(f, "", Set.of(), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AblageEinsatz(f, "", Set.of(".txt"), 1)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- Grösse ----------

    @Test
    @DisplayName("Grösse: zu grosse Dateien werden weder gelesen noch gespeichert noch hochgeladen")
    void groesse() {
        AblageAuswahl a = auswahl("ADMIN");
        assertThatThrownBy(() -> a.lies("gross.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.speichere("neu.txt", new byte[101])).isInstanceOf(IllegalArgumentException.class);

        UploadedFile f = mock(UploadedFile.class);
        when(f.getFileName()).thenReturn("hoch.txt");
        when(f.getSize()).thenReturn(101L);
        when(f.getContent()).thenReturn(new byte[101]);
        a.hochladen(new FileUploadEvent(mock(UIComponent.class), f, 1));
        assertThat(offen.dateien).doesNotContainKey("wurzel/m1/hoch.txt");
        assertThatThrownBy(() -> new AblageEinsatz(List.of(), "", Set.of(), AblageEinsatz.MAX_BYTES + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Hochladen: erlaubte Datei landet im aktuellen Ordner; ein Pfad im Dateinamen nicht")
    void hochladen() {
        AblageAuswahl a = auswahl("ADMIN");
        a.oeffneOrdner("unter");
        UploadedFile f = mock(UploadedFile.class);
        when(f.getFileName()).thenReturn("hoch.txt", "..\\..\\boese.txt");
        when(f.getSize()).thenReturn(3L);
        when(f.getContent()).thenReturn("abc".getBytes(StandardCharsets.UTF_8));
        a.hochladen(new FileUploadEvent(mock(UIComponent.class), f, 1));
        a.hochladen(new FileUploadEvent(mock(UIComponent.class), f, 1));
        assertThat(offen.dateien).containsKey("wurzel/m1/unter/hoch.txt");
        assertThat(offen.dateien.keySet()).noneMatch(k -> k.contains("boese"));
    }

    // ---------- Öffnen und Herunterladen ----------

    @Test
    @DisplayName("Öffnen liefert Inhalt; Herunterladen immer als octet-stream, Ordnerwechsel vergisst beides")
    void oeffnenUndHerunterladen() {
        AblageAuswahl a = auswahl("USER");
        a.oeffne("a.txt");
        assertThat(a.getGewaehlt()).isEqualTo("a.txt");
        assertThat(a.getInhalt()).asString(StandardCharsets.UTF_8).isEqualTo("hallo");
        a.herunterladen("a.txt");
        assertThat(a.getDownload().getContentType()).isEqualTo("application/octet-stream");
        assertThat(a.getDownload().getName()).isEqualTo("a.txt");
        a.oeffneOrdner("unter");
        assertThat(a.getGewaehlt()).isNull();
        assertThat(a.getInhalt()).isNull();
        assertThat(a.getDownload()).isNull();
        a.herunterladen("../a.txt");
        assertThat(a.getDownload()).isNull();
    }

    // ---------- Ordnerverwaltung (Karte 1475) ----------

    @Test
    @DisplayName("Ordner: ADMIN legt an, benennt um, verschiebt Datei und Ordner, löscht leer und mit Bestätigung")
    void ordnerverwaltung() throws IOException {
        AblageAuswahl a = auswahl("ADMIN");
        a.legeOrdnerAn("archiv");
        assertThat(a.getEintraege()).extracting(AblageAuswahl.Eintrag::name).contains("archiv");
        a.verschiebe("a.txt", "archiv/umbenannt.txt");
        a.verschiebe("unter", "archiv/unter");
        assertThat(offen.dateien).containsKeys("wurzel/m1/archiv/umbenannt.txt", "wurzel/m1/archiv/unter/c.drawio")
                .doesNotContainKeys("wurzel/m1/a.txt", "wurzel/m1/unter/c.drawio");

        assertThatThrownBy(() -> a.loesche("archiv", false)).isInstanceOf(java.nio.file.DirectoryNotEmptyException.class);
        a.legeOrdnerAn("leer");
        a.loesche("leer", false);
        a.loesche("gross.txt", false);
        assertThat(offen.dateien).doesNotContainKeys("wurzel/m1/leer/.keep", "wurzel/m1/gross.txt");

        // Oberfläche: rekursives Löschen erst mit dem Häkchen
        a.markiere("archiv", AblageAuswahl.LOESCHEN);
        assertThat(a.isMarkiertOrdner()).isTrue();
        a.bestaetige();
        assertThat(offen.dateien).containsKey("wurzel/m1/archiv/umbenannt.txt");
        assertThat(a.getMarkiert()).as("bleibt vorgemerkt").isEqualTo("archiv");
        a.setMitInhalt(true);
        a.bestaetige();
        assertThat(offen.dateien.keySet()).noneMatch(k -> k.startsWith("wurzel/m1/archiv"));
        assertThat(a.getMarkiert()).isNull();
        assertThat(offen.dateien).as("nichts ausserhalb der Wurzel angefasst").containsKeys("wurzel/m2/fremd.txt", "ausserhalb.txt");
    }

    @Test
    @DisplayName("Ordner: ohne Schreibrecht weder anlegen, verschieben noch löschen, auch nicht am Tag vorbei")
    void ordnerOhneSchreibrecht() {
        AblageAuswahl a = auswahl("USER");
        assertThatThrownBy(() -> a.legeOrdnerAn("neu")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> a.verschiebe("a.txt", "b.txt")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> a.loesche("a.txt", true)).isInstanceOf(SecurityException.class);
        a.markiere("a.txt", AblageAuswahl.LOESCHEN);
        a.bestaetige();
        assertThat(offen.dateien).containsKey("wurzel/m1/a.txt").doesNotContainKey("wurzel/m1/neu/.keep");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../m2/a.txt", "../../a.txt", "/abs.txt", "a//b.txt", "unter/../../x.txt", "x\\y.txt", "", " ",
            "unter/./a.txt", "unter/"})
    @DisplayName("Ordner: Verschieben an ein ungültiges Ziel wird abgelehnt, bevor die Ablage es sieht")
    void verschiebenTraversal(String ziel) {
        AblageAuswahl a = auswahl("ADMIN");
        offen.zugriffe.clear();
        assertThatThrownBy(() -> a.verschiebe("a.txt", ziel)).isInstanceOf(IllegalArgumentException.class);
        assertThat(offen.dateien).containsKey("wurzel/m1/a.txt");
        assertThat(offen.zugriffe).allMatch(p -> p.equals("wurzel/m1"));
    }

    @Test
    @DisplayName("Ordner: nicht in sich selbst, kein verbotener Typ im Ziel, keine ungültigen Namen")
    void ordnerGrenzen() {
        AblageAuswahl a = auswahl("ADMIN");
        assertThatThrownBy(() -> a.verschiebe("unter", "unter/tiefer")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("in sich selbst");
        assertThatThrownBy(() -> a.verschiebe("unter", "unter")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.verschiebe("a.txt", "a.html")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.verschiebe("b.exe", "b.txt")).as("unsichtbarer Typ").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> a.loesche("b.exe", false)).isInstanceOf(IllegalArgumentException.class);
        for (String n : List.of("..", ".", "a/b", "", "a\\b")) {
            assertThatThrownBy(() -> a.legeOrdnerAn(n)).as(n).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> a.loesche(n, true)).as(n).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(offen.dateien).containsKeys("wurzel/m1/unter/c.drawio", "wurzel/m1/a.txt", "wurzel/m1/b.exe");
    }
}
