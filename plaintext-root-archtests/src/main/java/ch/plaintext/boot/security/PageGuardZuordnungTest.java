/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import ch.plaintext.MenuRegistry;
import ch.plaintext.arch.ReactorLayout;
import ch.plaintext.boot.menu.MenuAnnotation;
import ch.plaintext.boot.menu.MenuItemImpl;
import ch.plaintext.boot.menu.MenuRegistryImpl;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Karte 523: Jede ausgelieferte View dieser Anwendung muss eine Zugriffsregel haben.
 *
 * <p><b>Der Defekt.</b> Der {@code PageAccessGuardService} lief hier im Modus {@code REPORT} und
 * meldete beim Start zwoelf Views ohne jede Regel — darunter {@code /rechnungbearbeiten},
 * {@code /rechnungdetail}, {@code /gearEdit} und {@code /wiki-edit}. Alle vier sind vollstaendige
 * Bearbeitungsmasken und waren fuer <i>jeden</i> angemeldeten Benutzer per Direkt-URL erreichbar.
 * {@code gearEdit} und {@code rechnungbearbeiten} haben im ganzen Repository nicht einmal einen
 * Link — sie sind ausschliesslich so erreichbar und dadurch unsichtbar offen.
 *
 * <p><b>Warum ein Test und nicht Sorgfalt.</b> Eine View ohne Regel faellt niemandem auf: sie
 * funktioniert, sie sieht richtig aus, und der einzige Hinweis ist eine WARN-Zeile im Startlog,
 * die zwischen tausend anderen steht. Erst der Umstieg auf {@code mode=STRICT} macht daraus einen
 * sichtbaren Fehler — und dann in Produktion. Dieser Test zieht das in den Build vor: eine neue
 * View ohne {@code @MenuAnnotation}, Alias oder Allowlist-Eintrag laesst ihn fehlschlagen.
 *
 * <p><b>Was er misst.</b> Er baut denselben Zustand nach, den die laufende Anwendung hat, und
 * benutzt dann die <i>echte</i> Guard-Logik ({@link PageAccessGuardService#istZugeordnet(String)}),
 * statt sie nachzubilden:
 * <ul>
 *   <li>die Views aus dem Klassenpfad — also auch die aus den Modul-JARs, nicht nur die dieses
 *       Repositories (dieselbe Suche wie {@code PageAccessGuardStartupReport}),</li>
 *   <li>die Menuepunkte aus den {@code @MenuAnnotation}-Klassen des Klassenpfads,</li>
 *   <li>Modus, Aliase und Allowlist aus der echten {@code application.yml}, gebunden mit dem
 *       Spring-Boot-Binder.</li>
 * </ul>
 *
 * <p><b>Grenze, absichtlich benannt</b> (Systemprompt 6.16): Geprueft wird die <i>Zuordnung</i>,
 * nicht die Rollenlage dahinter. Dass {@code rechnungdetail} wie {@code rechnungen.html} bewacht
 * wird, sagt dieser Test; ob {@code rechnungen.html} die richtigen Rollen traegt, sagt er nicht.
 *
 * <p><b>Karte 1298: jetzt in {@code plaintext-root-archtests}.</b> Bis zum 22.09.2026 stand diese
 * Pruefung wortgleich in app, guild und schuetu — als bewusste Duplikation, weil Karte 523 jede
 * Anwendung einzeln auf {@code STRICT} umstellen und einzeln zurueckdrehen wollte. Der Kommentar
 * nannte die Bedingung fuer den Umzug selbst: „Sobald alle Apps in STRICT laufen, gehoert er
 * dorthin — dann laeuft er auch in iot und fwtool." Am 22.09.2026 stehen alle sechs
 * {@code application.yml} auf {@code STRICT} (root als Vorgabe {@code ${...:STRICT}}); die
 * Bedingung ist erfuellt. Der Test liegt im Paket {@code ch.plaintext.boot.security}, weil er
 * {@link PageAccessGuardService#kanonisch(String)} (paketsichtbar) benutzt — wie die Kopien vorher.
 *
 * <p>Zwei Aenderungen gegenueber den Kopien, beide fuer die neuen Consumer noetig:
 * <ul>
 *   <li>Platzhalter in der {@code application.yml} werden aufgeloest
 *       ({@code mode: ${PLAINTEXT_SECURITY_PAGE_GUARD_MODE:STRICT}} in root) — wie in der laufenden
 *       Anwendung, ohne gesetzte Umgebungsvariable also mit dem Vorgabewert.</li>
 *   <li>Die Ausnahmen fuer fremde Views stehen nicht mehr als Konstante in der Klasse, sondern je
 *       Consumer in {@code page-guard-vertrag.properties} im Test-Classpath
 *       ({@code ausnahme.<kanonischer-view-name> = Grund}); ohne Datei gibt es keine. Heute hat
 *       kein Repo eine.</li>
 * </ul>
  */
class PageGuardZuordnungTest {

    /** Dieselbe Suche wie im {@code PageAccessGuardStartupReport}. */
    private static final String VIEW_MUSTER = "classpath*:META-INF/resources/**/*.xhtml";

    /** Pfadbestandteile, die ein Facelet als Fragment/Template kennzeichnen. */
    private static final List<String> FRAGMENT_MARKER = List.of("/includes/", "/templates/");

    /** Ein {@code ui:composition} OHNE {@code template=} ist ein Include-Fragment, keine Seite. */
    private static final Pattern UI_COMPOSITION = Pattern.compile("<ui:composition\\b([^>]*)>", Pattern.DOTALL);

    private static final String MENU_BASISPAKET = "ch.plaintext";

    /**
     * Untergrenze der ausgelieferten Views je Reactor (Schluessel = artifactId der Wurzel-pom). app,
     * guild und schuetu trugen in ihren Kopien 40. root am 22.09.2026 gemessen: 37 Views am
     * Klassenpfad der root-webapp — die 40 der Kopien haetten root rot gefaerbt, ohne dass etwas
     * fehlt. iot und fwtool ziehen die root-Views ueber plaintext-root-webapp mit herein.
     */
    static final Map<String, Integer> MINDESTENS_VIEWS_JE_REACTOR = Map.of(
            "plaintext-root-parent", 30,
            "plaintext-parent", 40,
            "plaintext-guild-parent", 40,
            "plaintext-schuetu-parent", 40);

    /** Fuer einen hier nicht gelisteten Reactor: die Groessenordnung der root-Views. */
    private static final int MINDESTENS_VIEWS_SONST = 30;

    /** artifactId der Wurzel-pom des laufenden Reactors, siehe {@link ReactorLayout#reactorArtifactId()}. */
    private static String reactor() {
        return ReactorLayout.reactorArtifactId();
    }

    /**
     * Views aus einer FREMDEN Abhaengigkeit, die hier (noch) keine Regel haben koennen.
     *
     * <p>Gelesen aus {@link #VERTRAG} des Consumers. <b>Zurzeit in keinem Repo belegt.</b> Der letzte Eintrag war {@code demo} aus {@code plaintext-root-webapp}
     * — dort geloescht (Karte 523, PR plaintext-root#38), aber bis zum Bump noch im gepinnten
     * Framework-JAR. Mit dem Bump auf {@code plaintext-root} 1.502.0 (Karte 529) ist die View aus
     * dem Klassenpfad verschwunden, und {@link #dieAusnahmenSindNochNoetig()} hat den Eintrag
     * daraufhin als ueberfluessig gemeldet — genau wie es der Hinweis an dieser Stelle angekuendigt
     * hatte. Ersatzlos gestrichen.
     *
     * <p>Ein Eintrag hier entschuldigt nur eine fehlende Regel, er legt keine an: Die View bleibt
     * gesperrt. Neue Eintraege gehoeren mit Grund und Verfallsbedingung versehen.
     */
    private static final Set<String> FREMDE_AUSNAHMEN = ausnahmenLaden();

    /** Vertragsdatei des Consumers mit den begruendeten fremden Ausnahmen (optional). */
    static final String VERTRAG = "page-guard-vertrag.properties";

    private static Set<String> ausnahmenLaden() {
        Properties p = new Properties();
        try (InputStream in = PageGuardZuordnungTest.class.getClassLoader().getResourceAsStream(
                "page-guard-vertrag.properties")) {
            if (in == null) {
                return Set.of();
            }
            p.load(in);
        } catch (IOException e) {
            throw new IllegalStateException("page-guard-vertrag.properties nicht lesbar", e);
        }
        Set<String> ausnahmen = new TreeSet<>();
        for (String k : p.stringPropertyNames()) {
            if (k.startsWith("ausnahme.")) {
                ausnahmen.add(k.substring("ausnahme.".length()));
            }
        }
        return Set.copyOf(ausnahmen);
    }

    // ------------------------------------------------------------------ Test 1

    @Test
    void jedeAusgelieferteViewHatEineZugriffsregel() throws IOException {
        PageAccessGuardService guard = guardAusEchterKonfiguration();
        Map<String, String> views = ausgelieferteViews();

        int mindestens = ReactorLayout.mindestensFuerDiesenReactor(MINDESTENS_VIEWS_JE_REACTOR, MINDESTENS_VIEWS_SONST);
        assertTrue(views.size() >= mindestens,
                "Es wurden nur " + views.size() + " Views gefunden (festgehalten fuer " + reactor() + ": "
                        + mindestens + ") — dann sucht der Test an der falschen Stelle und ein gruener "
                        + "Lauf waere wertlos.");

        List<String> ohneRegel = new ArrayList<>();
        views.forEach((kanonisch, quelle) -> {
            if (FREMDE_AUSNAHMEN.contains(kanonisch)) {
                return;
            }
            if (!guard.istZugeordnet("/" + kanonisch + ".xhtml")) {
                ohneRegel.add(kanonisch + "   (" + quelle + ")");
            }
        });

        // Selbstauskunft: was wurde geprueft, und was bewusst nicht (Systemprompt 6.16).
        System.out.println("PageGuardZuordnungTest: " + views.size() + " ausgelieferte Views geprueft, "
                + FREMDE_AUSNAHMEN.size() + " fremde Ausnahme(n) uebersprungen " + FREMDE_AUSNAHMEN
                + ", " + ohneRegel.size() + " ohne Regel.");

        if (!ohneRegel.isEmpty()) {
            fail("Diese Views haben keine Zugriffsregel. In mode=REPORT sind sie fuer JEDEN "
                    + "angemeldeten Benutzer per Direkt-URL erreichbar, in mode=STRICT sind sie fuer "
                    + "alle gesperrt — beides ist falsch. Abhilfe: @MenuAnnotation(link=\"….html\") "
                    + "ergaenzen, oder in application.yml unter "
                    + "plaintext.security.page-guard.aliases die Listenseite zuordnen (bevorzugt, "
                    + "die View erbt dann deren Rollen), oder — nur wenn die Seite wirklich fuer "
                    + "jeden Angemeldeten offen sein soll — .allowlist:\n  "
                    + String.join("\n  ", ohneRegel));
        }
    }

    // ------------------------------------------------------------------ Test 2

    /**
     * Gegenprobe zur Suche selbst: ohne sie waere Test 1 auch dann gruen, wenn
     * {@code istZugeordnet} pauschal {@code true} lieferte oder die View-Suche nichts faende.
     */
    @Test
    void diePruefungSchlaegtBeiEinerViewOhneRegelUeberhauptAn() throws IOException {
        PageAccessGuardService guard = guardAusEchterKonfiguration();

        assertFalse(guard.istZugeordnet("/gibt-es-nicht-und-hat-keine-regel.xhtml"),
                "Eine View ohne Menueeintrag, Alias und Allowlist-Eintrag muss als NICHT zugeordnet "
                        + "gelten — sonst sagt Test 1 nichts aus.");
        assertTrue(guard.istZugeordnet("/home.xhtml"),
                "Systemseiten muessen als zugeordnet gelten — sonst misst der Test etwas anderes "
                        + "als den Guard.");
    }

    // ------------------------------------------------------------------ Test 3

    @Test
    void derGuardLaeuftInStrict() {
        assertEquals(PageGuardMode.STRICT, eigenschaften().getMode(),
                "plaintext.security.page-guard.mode muss STRICT sein. In REPORT meldet der Guard "
                        + "Views ohne Regel nur, laesst sie aber fuer jeden Angemeldeten passieren "
                        + "(Karte 523).");
    }

    // ------------------------------------------------------------------ Test 4

    /**
     * Haelt die Ausnahmenliste sauber: eine Ausnahme fuer eine View, die es gar nicht mehr gibt,
     * taeuscht Abdeckung vor und ueberlebt sonst jeden Aufraeumlauf.
     */
    @Test
    void dieAusnahmenSindNochNoetig() throws IOException {
        Map<String, String> views = ausgelieferteViews();
        PageAccessGuardService guard = guardAusEchterKonfiguration();

        List<String> ueberfluessig = new ArrayList<>();
        for (String ausnahme : FREMDE_AUSNAHMEN) {
            if (!views.containsKey(ausnahme)) {
                ueberfluessig.add(ausnahme + " — die View wird nicht mehr ausgeliefert");
            } else if (guard.istZugeordnet("/" + ausnahme + ".xhtml")) {
                ueberfluessig.add(ausnahme + " — die View hat inzwischen eine Regel");
            }
        }
        assertTrue(ueberfluessig.isEmpty(),
                "FREMDE_AUSNAHMEN enthaelt Eintraege, die nicht mehr gebraucht werden — "
                        + "bitte streichen:\n  " + String.join("\n  ", ueberfluessig));
    }

    // ------------------------------------------------------------------ Helfer

    /**
     * Der echte Guard, gefuettert mit den echten Menuepunkten und der echten application.yml.
     *
     * <p>Die Menuepunkte kommen ueber eine echte {@link MenuRegistryImpl} in einem kleinen
     * Spring-Kontext — nicht ueber eine selbstgebaute {@link MenuRegistry}. Grund: Der Guard nimmt
     * fuer {@code MenuRegistryImpl} den Weg ueber {@code getAllMenuItemsImpl()}; nur der liefert
     * {@code MenuItemImpl}. Der allgemeine Weg ueber {@code getAllMenuItems()} kaeme hier gar nicht
     * durch, weil {@code MenuItemImpl} das Interface {@code MenuRegistry.MenuItem} nicht
     * implementiert. Ein Nachbau haette also etwas anderes gemessen als die Anwendung tut.
     */
    private PageAccessGuardService guardAusEchterKonfiguration() throws IOException {
        List<MenuItemImpl> menues = menueEintraegeAusKlassenpfad();
        assertFalse(menues.isEmpty(),
                "Keine @MenuAnnotation im Klassenpfad gefunden — dann kennt der Test keine "
                        + "Menuepunkte und jede View saehe ungeschuetzt aus.");
        GenericApplicationContext kontext = new GenericApplicationContext();
        kontext.refresh();
        for (int i = 0; i < menues.size(); i++) {
            kontext.getBeanFactory().registerSingleton("menuepunkt" + i, menues.get(i));
        }
        return new PageAccessGuardService(new MenuRegistryImpl(kontext), eigenschaften());
    }

    /**
     * {@code plaintext.security} aus der echten application.yml, gebunden mit dem Spring-Boot-Binder
     * — also derselbe Weg, auf dem die laufende Anwendung ihre Konfiguration bekommt.
     *
     * <p>Der Typ folgt der in {@code plaintext-root.version} gepinnten Framework-Fassung. Bis
     * 1.491.0 war {@link PageGuardProperties} eine innere Klasse von
     * {@code PlaintextSecurityProperties} und der Guard nahm die aeussere entgegen; seit 1.492.0
     * ist sie herausgeloest und traegt den Praefix selbst. **Mit dem Bump auf 1.502.0 (Karte 529)
     * hier nachgezogen** — der Compiler hatte es angesagt, wie es der frühere Hinweis an dieser
     * Stelle vorhergesagt hat.
     *
     * <p>Der wirksame Praefix {@code plaintext.security.page-guard} ist beim Herausloesen
     * unveraendert geblieben; die {@code application.yml} musste deshalb nicht angefasst werden.
     */
    private PageGuardProperties eigenschaften() {
        try {
            List<PropertySource<?>> geladen =
                    new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
            MutablePropertySources quellen = new MutablePropertySources();
            geladen.forEach(quellen::addLast);
            return new Binder(ConfigurationPropertySources.from(quellen),
                    new PropertySourcesPlaceholdersResolver(quellen))
                    .bind("plaintext.security.page-guard", PageGuardProperties.class)
                    .orElseThrow(() -> new IllegalStateException(
                            "plaintext.security.page-guard fehlt in application.yml"));
        } catch (IOException e) {
            throw new IllegalStateException("application.yml nicht lesbar", e);
        }
    }

    /** Kanonischer View-Name -> Fundort. Fragmente und Templates bleiben aussen vor. */
    private Map<String, String> ausgelieferteViews() throws IOException {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Map<String, String> ergebnis = new TreeMap<>();
        for (Resource resource : resolver.getResources(VIEW_MUSTER)) {
            String url = resource.getURL().toString();
            int index = url.lastIndexOf("META-INF/resources/");
            if (index < 0) {
                continue;
            }
            String relativ = url.substring(index + "META-INF/resources/".length());
            if (FRAGMENT_MARKER.stream().anyMatch(("/" + relativ)::contains) || istIncludeFragment(resource)) {
                continue;
            }
            ergebnis.putIfAbsent(PageAccessGuardService.kanonisch(relativ), kurz(url));
        }
        return ergebnis;
    }

    /** Alle {@code @MenuAnnotation}-Klassen des Klassenpfads als Menuepunkte. */
    private List<MenuItemImpl> menueEintraegeAusKlassenpfad() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(MenuAnnotation.class));

        List<MenuItemImpl> ergebnis = new ArrayList<>();
        scanner.findCandidateComponents(MENU_BASISPAKET).forEach(definition -> {
            String klassenName = definition.getBeanClassName();
            if (klassenName == null) {
                return;
            }
            Class<?> klasse;
            try {
                klasse = Class.forName(klassenName, false, getClass().getClassLoader());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                return;
            }
            MenuAnnotation annotation = klasse.getAnnotation(MenuAnnotation.class);
            if (annotation == null) {
                return;
            }
            MenuItemImpl item = new MenuItemImpl();
            item.setTitle(annotation.title());
            item.setParent(annotation.parent());
            item.setCommand(annotation.link());
            item.setRoles(new ArrayList<>(List.of(annotation.roles())));
            ergebnis.add(item);
        });
        return ergebnis;
    }

    private boolean istIncludeFragment(Resource resource) throws IOException {
        try (InputStream stream = resource.getInputStream()) {
            String inhalt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Matcher composition = UI_COMPOSITION.matcher(inhalt);
            return composition.find() && !composition.group(1).contains("template=");
        }
    }

    /** Nur der lesbare Rest der URL — der absolute Pfad der Buildmaschine hilft niemandem. */
    private String kurz(String url) {
        int jar = url.indexOf(".jar!");
        if (jar >= 0) {
            int start = url.lastIndexOf('/', jar) + 1;
            return url.substring(start, jar + 4);
        }
        int klassen = url.lastIndexOf("/target/classes/");
        return klassen >= 0 ? url.substring(url.lastIndexOf('/', klassen - 1) + 1) : url;
    }
}
