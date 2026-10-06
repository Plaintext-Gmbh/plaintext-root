/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules.katalog;

import ch.plaintext.sidecars.SidecarRegister;
import ch.plaintext.sidecars.SidecarStand;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.util.ClassUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Karte 1422 (Daniel 04.10.2026): Analyse des Modulzusammenspiels der <b>laufenden</b> Version, aus
 * dem Schnittstellen-Katalog und den Spring-Beans. Liest nur; ruft keine Fachmethode auf.
 *
 * <ul>
 *   <li><b>Nutzer</b> einer Schnittstelle: jede Bean aus {@code ch.plaintext.*}, die sie über ein Feld
 *       oder einen Konstruktor-Parameter bezieht — direkt (Pflicht) oder über {@code ObjectProvider},
 *       {@code Optional}, {@code List}/{@code Set}/{@code Collection} (optional: fehlt der Umsetzer,
 *       läuft der Nutzer weiter).</li>
 *   <li><b>Modulbild</b>: was ein Modul anbietet, umsetzt und nutzt.</li>
 *   <li><b>Weglassen</b>: welche Pflicht-Nutzer brechen, wenn ein Modul fehlt, das den einzigen
 *       Umsetzer einer Schnittstelle stellt.</li>
 *   <li><b>Bauplan</b> zu einer Fragestellung: passende Schnittstellen, MCP-Werkzeuge,
 *       Sidecar-Fähigkeiten und Lücken.</li>
 * </ul>
 */
@Slf4j
@Service
public class ModulAnalyse {

    static final String MCP_TOOL = "org.springframework.ai.mcp.annotation.McpTool";
    private static final Set<String> OPTIONAL_HUELLEN = Set.of(ObjectProvider.class.getName(), Optional.class.getName(),
            List.class.getName(), Set.class.getName(), Collection.class.getName(), "jakarta.inject.Provider");

    private final SchnittstellenKatalog katalog;
    private final ApplicationContext kontext;
    private final ObjectProvider<SidecarRegister> sidecars;
    private final AtomicReference<List<Nutzung>> nutzungen = new AtomicReference<>();

    public ModulAnalyse(SchnittstellenKatalog katalog, ApplicationContext kontext, ObjectProvider<SidecarRegister> sidecars) {
        this.katalog = katalog;
        this.kontext = kontext;
        this.sidecars = sidecars;
    }

    /** Eine Bean, die eine Katalog-Schnittstelle bezieht. */
    public record Nutzung(String schnittstelle, String bean, String klasse, String modul, boolean optional) {
    }

    /** Was ein Modul anbietet (Katalog), umsetzt (Beans) und nutzt (Injektion). */
    public record ModulBild(String modul, List<String> bietetAn, List<String> setztUm, List<String> nutzt) {
    }

    /** Eine Schnittstelle im Zusammenspiel. */
    public record SchnittstellenBild(String name, String art, String stabilitaet, String modul,
                                     List<String> umgesetztIn, List<Nutzung> nutzer, List<String> verwendetIn) {
    }

    /** Was bricht, wenn {@code modul} fehlt. */
    public record Folge(String modul, List<String> brichtPflicht, List<String> faelltWegOptional) {
    }

    /** Gesamtbild der laufenden Version. */
    public record Analyse(List<ModulBild> module, List<SchnittstellenBild> schnittstellen, List<String> ohneUmsetzer,
                         List<Folge> weglassen) {
    }

    /** Ein MCP-Werkzeug dieser Anwendung. */
    public record Werkzeug(String name, String beschreibung) {
    }

    /** Eine Sidecar-Fähigkeit. */
    public record Faehigkeit(String sidecar, String faehigkeit, boolean erreichbar) {
    }

    /** Antwort des Bauplans. */
    public record Bauplan(String frage, List<String> begriffe, List<SchnittstellenBild> schnittstellen,
                          List<Werkzeug> werkzeuge, List<Faehigkeit> faehigkeiten, List<String> luecken) {
    }

    // ── Nutzer ─────────────────────────────────────────────

    /** @return alle Nutzungen von Katalog-Schnittstellen; einmal ermittelt, dann gehalten */
    public List<Nutzung> nutzungen() {
        List<Nutzung> l = nutzungen.get();
        if (l == null) {
            l = ermittle();
            nutzungen.set(l);
        }
        return l;
    }

    /** @param schnittstelle voller Name aus dem Katalog */
    public List<Nutzung> nutzer(String schnittstelle) {
        return nutzungen().stream().filter(n -> n.schnittstelle().equals(schnittstelle)).toList();
    }

    List<Nutzung> ermittle() {
        Map<String, String> binaerZuName = new LinkedHashMap<>();
        katalog.alle().forEach(s -> binaerZuName.put(SchnittstellenKatalog.binaerName(s.name()), s.name()));
        if (!(kontext instanceof ConfigurableApplicationContext c)) {
            return List.of();
        }
        ConfigurableListableBeanFactory fabrik = c.getBeanFactory();
        List<Nutzung> raus = new ArrayList<>();
        for (String bean : fabrik.getBeanDefinitionNames()) {
            Class<?> typ;
            try {
                typ = fabrik.getType(bean, false);
            } catch (RuntimeException | LinkageError _) {
                continue;
            }
            if (typ == null) {
                continue;
            }
            Class<?> k = ClassUtils.getUserClass(typ);
            if (!k.getName().startsWith("ch.plaintext.")) {
                continue;
            }
            Set<String> gesehen = new LinkedHashSet<>();
            for (Bezug b : bezuege(k)) {
                String name = binaerZuName.get(b.typ());
                if (name != null && gesehen.add(name + b.optional())) {
                    raus.add(new Nutzung(name, bean, k.getName(), SchnittstellenKatalog.modul(k), b.optional()));
                }
            }
        }
        raus.sort(Comparator.comparing(Nutzung::schnittstelle).thenComparing(Nutzung::klasse));
        return List.copyOf(raus);
    }

    /** Ein Injektionspunkt: Binärname des bezogenen Typs und ob er über eine Hülle (optional) kommt. */
    record Bezug(String typ, boolean optional) {
    }

    /** Felder (auch geerbte) und Konstruktor-Parameter einer Klasse. */
    static List<Bezug> bezuege(Class<?> k) {
        List<Bezug> raus = new ArrayList<>();
        try {
            for (Class<?> c = k; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    bezug(f.getGenericType()).ifPresent(raus::add);
                }
            }
            for (Constructor<?> ctor : k.getDeclaredConstructors()) {
                for (Type t : ctor.getGenericParameterTypes()) {
                    bezug(t).ifPresent(raus::add);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            log.debug("Modul-Analyse: {} nicht lesbar: {}", k.getName(), e.getMessage());
        }
        return raus;
    }

    static Optional<Bezug> bezug(Type t) {
        if (t instanceof Class<?> c) {
            return c.isPrimitive() ? Optional.empty() : Optional.of(new Bezug(c.getName(), false));
        }
        if (t instanceof ParameterizedType p && p.getRawType() instanceof Class<?> roh
                && OPTIONAL_HUELLEN.contains(roh.getName()) && p.getActualTypeArguments().length == 1) {
            Type innen = p.getActualTypeArguments()[0];
            Class<?> c = innen instanceof Class<?> ic ? ic
                    : innen instanceof ParameterizedType ip && ip.getRawType() instanceof Class<?> ir ? ir : null;
            return c == null ? Optional.empty() : Optional.of(new Bezug(c.getName(), true));
        }
        if (t instanceof ParameterizedType p && p.getRawType() instanceof Class<?> roh) {
            return Optional.of(new Bezug(roh.getName(), false));
        }
        return Optional.empty();
    }

    // ── Gesamtbild ─────────────────────────────────────────

    public Analyse analysiere() {
        List<SchnittstellenKatalog.Schnittstelle> alle = katalog.alle();
        List<SchnittstellenBild> bilder = alle.stream().map(s -> bild(s, alle)).toList();

        Map<String, Set<String>[]> proModul = new java.util.TreeMap<>();
        for (SchnittstellenBild b : bilder) {
            eintrag(proModul, b.modul())[0].add(b.name());
            b.umgesetztIn().forEach(m -> eintrag(proModul, m)[1].add(b.name()));
            b.nutzer().forEach(n -> eintrag(proModul, n.modul())[2].add(b.name()));
        }
        List<ModulBild> module = proModul.entrySet().stream()
                .map(e -> new ModulBild(e.getKey(), List.copyOf(e.getValue()[0]), List.copyOf(e.getValue()[1]),
                        List.copyOf(e.getValue()[2])))
                .toList();

        List<String> ohneUmsetzer = bilder.stream()
                .filter(b -> !"DTO".equals(b.art()) && b.umgesetztIn().isEmpty() && !b.nutzer().isEmpty())
                .map(SchnittstellenBild::name).toList();
        return new Analyse(module, bilder, ohneUmsetzer, folgen(bilder));
    }

    @SuppressWarnings("unchecked")
    private static Set<String>[] eintrag(Map<String, Set<String>[]> m, String modul) {
        return m.computeIfAbsent(modul, x -> new Set[]{new TreeSet<>(), new TreeSet<>(), new TreeSet<>()});
    }

    private SchnittstellenBild bild(SchnittstellenKatalog.Schnittstelle s, List<SchnittstellenKatalog.Schnittstelle> alle) {
        List<String> umgesetztIn = s.umsetzer().stream().map(SchnittstellenKatalog.Umsetzer::modul).distinct().sorted().toList();
        // Ein DTO lebt in den Signaturen anderer Verträge: dort steht, wer es übergibt.
        List<String> verwendetIn = !s.istDto() ? List.of() : alle.stream()
                .filter(x -> !x.name().equals(s.name()) && x.methoden().stream().anyMatch(m -> nennt(m, s.name())))
                .map(SchnittstellenKatalog.Schnittstelle::name).toList();
        return new SchnittstellenBild(s.name(), s.art(), s.stabilitaet(), s.modul(), umgesetztIn, nutzer(s.name()), verwendetIn);
    }

    private static boolean nennt(SchnittstellenKatalog.Methode m, String typ) {
        return m.rueckgabe().contains(typ) || m.parameter().stream().anyMatch(p -> p.typ().contains(typ));
    }

    /** Je Modul, das für mindestens eine genutzte Schnittstelle der EINZIGE Umsetzer ist. */
    static List<Folge> folgen(List<SchnittstellenBild> bilder) {
        Map<String, List<String>[]> proModul = new java.util.TreeMap<>();
        for (SchnittstellenBild b : bilder) {
            if (b.umgesetztIn().size() != 1) {
                continue;
            }
            String einziger = b.umgesetztIn().getFirst();
            for (Nutzung n : b.nutzer()) {
                if (n.modul().equals(einziger)) {
                    continue;   // nutzt sich selbst: fällt mit weg
                }
                @SuppressWarnings("unchecked")
                List<String>[] f = proModul.computeIfAbsent(einziger, x -> new List[]{new ArrayList<>(), new ArrayList<>()});
                f[n.optional() ? 1 : 0].add(n.modul() + ": " + n.klasse() + " → " + b.name());
            }
        }
        return proModul.entrySet().stream()
                .map(e -> new Folge(e.getKey(), List.copyOf(e.getValue()[0]), List.copyOf(e.getValue()[1]))).toList();
    }

    // ── Bauplan ────────────────────────────────────────────

    /** Begriffe einer Frage: Wörter ab vier Buchstaben, klein, ohne häufige Füllwörter. */
    static List<String> begriffe(String frage) {
        Set<String> fuell = Set.of("eine", "einen", "einem", "einer", "oder", "und", "per", "über", "ueber", "mit",
                "für", "fuer", "wenn", "dann", "soll", "sollen", "kann", "können", "alle", "auch", "nach", "eines");
        Set<String> raus = new LinkedHashSet<>();
        for (String w : (frage == null ? "" : frage).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 4 && !fuell.contains(w)) {
                raus.add(w);
            }
        }
        return List.copyOf(raus);
    }

    public Bauplan bauplan(String frage) {
        List<String> begriffe = begriffe(frage);
        Map<String, Integer> treffer = new LinkedHashMap<>();
        Set<String> gefunden = new LinkedHashSet<>();
        for (String b : begriffe) {
            for (SchnittstellenKatalog.Schnittstelle s : katalog.suche(b)) {
                treffer.merge(s.name(), 1, Integer::sum);
                gefunden.add(b);
            }
        }
        List<SchnittstellenKatalog.Schnittstelle> alle = katalog.alle();
        List<SchnittstellenBild> schnittstellen = treffer.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(25)
                .map(e -> katalog.eine(e.getKey()).map(s -> bild(s, alle)).orElse(null))
                .filter(java.util.Objects::nonNull).toList();

        List<Werkzeug> werkzeuge = werkzeuge().stream().filter(w -> {
            String h = (w.name() + " " + w.beschreibung()).toLowerCase(Locale.ROOT);
            boolean passt = begriffe.stream().anyMatch(h::contains);
            begriffe.stream().filter(h::contains).forEach(gefunden::add);
            return passt;
        }).limit(25).toList();

        List<Faehigkeit> faehigkeiten = new ArrayList<>();
        SidecarRegister reg = sidecars.getIfAvailable();
        if (reg != null) {
            for (SidecarStand st : reg.alle()) {
                for (String f : st.faehigkeiten() == null ? List.<String>of() : st.faehigkeiten()) {
                    String h = (st.name() + " " + f).toLowerCase(Locale.ROOT);
                    if (begriffe.stream().anyMatch(h::contains)) {
                        faehigkeiten.add(new Faehigkeit(st.name(), f, st.erreichbar()));
                        begriffe.stream().filter(h::contains).forEach(gefunden::add);
                    }
                }
            }
        }
        List<String> luecken = begriffe.stream().filter(b -> !gefunden.contains(b)).toList();
        return new Bauplan(frage, begriffe, schnittstellen, werkzeuge, List.copyOf(faehigkeiten), luecken);
    }

    /** Alle {@code @McpTool}-Methoden der Beans (über den Annotationsnamen, ohne Abhängigkeit zu Spring AI). */
    public List<Werkzeug> werkzeuge() {
        List<Werkzeug> raus = new ArrayList<>();
        Set<String> namen = new TreeSet<>();
        for (String bean : kontext.getBeanDefinitionNames()) {
            Class<?> typ;
            try {
                typ = kontext.getType(bean, false);
            } catch (RuntimeException | LinkageError _) {
                continue;
            }
            if (typ == null || !ClassUtils.getUserClass(typ).getName().startsWith("ch.plaintext.")) {
                continue;
            }
            for (Method m : ClassUtils.getUserClass(typ).getDeclaredMethods()) {
                for (Annotation a : m.getAnnotations()) {
                    if (a.annotationType().getName().equals(MCP_TOOL)) {
                        String name = wert(a, "name");
                        if (!name.isEmpty() && namen.add(name)) {
                            raus.add(new Werkzeug(name, wert(a, "description")));
                        }
                    }
                }
            }
        }
        raus.sort(Comparator.comparing(Werkzeug::name));
        return raus;
    }

    private static String wert(Annotation a, String element) {
        try {
            Object o = a.annotationType().getMethod(element).invoke(a);
            return o == null ? "" : o.toString();
        } catch (ReflectiveOperationException | RuntimeException _) {
            return "";
        }
    }
}
