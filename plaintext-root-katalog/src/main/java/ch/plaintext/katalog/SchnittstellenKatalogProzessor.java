/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.katalog;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Karte 1405 (Daniel 02.10.2026): ein Katalog aller öffentlichen Schnittstellen der
 * {@code *-interfaces}-Module mit Zweck und Methoden, damit ein LLM über MCP neue Funktionen aus den
 * vorhandenen Bausteinen planen kann.
 *
 * <p>Javadoc gibt es zur Laufzeit nicht mehr; beim Kompilieren aber schon
 * ({@code Elements.getDocComment}). Dieser Prozessor schreibt deshalb je Modul
 * {@code META-INF/plaintext-katalog/<modul>.json} ins Jar: Schnittstelle, Zweck (Javadoc), Methoden
 * mit Rückgabe, Parametern und deren Javadoc. Der Zweck ist Pflicht: fehlt einer Schnittstelle das
 * Javadoc, meldet der Prozessor einen Fehler (mit {@code -Aplaintext.katalog.streng=false} nur eine
 * Warnung). Eine eigene Annotation braucht es dafür nicht, die Beschreibung steht nur an einer Stelle.</p>
 *
 * <p>Optionen: {@code plaintext.katalog.modul} (Pflicht, z. B. {@code plaintext-root-interfaces}),
 * {@code plaintext.katalog.streng} (Vorgabe {@code true}), {@code plaintext.katalog.alle} (alle
 * öffentlichen Schnittstellen statt nur annotierter; Vorgabe {@code true} genau für {@code *-interfaces}).</p>
 *
 * <p><b>Karte 1422 (Daniel 04.10.2026):</b> Der Prozessor läuft jetzt in <b>jedem</b> Modul (im
 * root-Parent vor Lombok eingebunden). Ausserhalb von {@code *-interfaces} nimmt er nur Schnittstellen
 * mit {@code @ModulApi} auf, dazu jede Klasse oder Methode mit {@code @ModulApiUmsetzung} als
 * Beschreibung einer Umsetzung. Die Annotationen werden über ihren Namen erkannt; der Prozessor hängt
 * nicht von {@code plaintext-root-interfaces} ab (er wird vor ihm gebaut). Findet er in einem Modul
 * nichts, schreibt er keine Datei.</p>
 */
@SupportedAnnotationTypes("*")
@SupportedOptions({SchnittstellenKatalogProzessor.OPTION_MODUL, SchnittstellenKatalogProzessor.OPTION_STRENG,
        SchnittstellenKatalogProzessor.OPTION_ALLE})
public class SchnittstellenKatalogProzessor extends AbstractProcessor {

    /** Beginn eines Eintrags mit Name (Karte 1416, Sonar java:S1192). */
    private static final String JSON_NAME_AUF = "{\"name\":";

    public static final String OPTION_MODUL = "plaintext.katalog.modul";
    public static final String OPTION_STRENG = "plaintext.katalog.streng";
    public static final String OPTION_ALLE = "plaintext.katalog.alle";
    static final String MODUL_API = "ch.plaintext.modules.ModulApi";
    static final String MODUL_API_UMSETZUNG = "ch.plaintext.modules.ModulApiUmsetzung";
    public static final String VERZEICHNIS = "META-INF/plaintext-katalog/";

    private final List<TypeElement> gefunden = new ArrayList<>();
    private final List<TypeElement> umsetzungen = new ArrayList<>();
    private boolean alle;

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        String modul = processingEnv.getOptions().get(OPTION_MODUL);
        if (!env.processingOver()) {
            alle = alleSchnittstellen(modul);
            for (Element e : env.getRootElements()) {
                sammle(e);
            }
            return false;
        }
        if (modul == null || modul.isBlank()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Schnittstellen-Katalog: Option -A" + OPTION_MODUL + " fehlt, kein Katalog geschrieben.");
            return false;
        }
        if (!alle && gefunden.isEmpty() && umsetzungen.isEmpty()) {
            return false;   // Fachmodul ohne @ModulApi: keine leere Datei ins Jar
        }
        boolean streng = !"false".equalsIgnoreCase(processingEnv.getOptions().get(OPTION_STRENG));
        Diagnostic.Kind fehler = streng ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING;
        gefunden.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        umsetzungen.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        String herkunft = modul.endsWith("-interfaces") ? "interfaces-Modul" : "im Modul";
        StringBuilder json = new StringBuilder("{\"modul\":").append(Json.text(modul)).append(",\"schnittstellen\":[");
        boolean erste = true;
        for (TypeElement t : gefunden) {
            String zweck = javadoc(t);
            if (zweck.isEmpty()) {
                processingEnv.getMessager().printMessage(fehler,
                        "Schnittstellen-Katalog (Karte 1405): " + t.getQualifiedName()
                                + " braucht ein Javadoc, das den Zweck beschreibt.", t);
            }
            json.append(erste ? "" : ",").append(schnittstelle(t, zweck, herkunft, fehler));
            erste = false;
        }
        json.append("],\"umsetzungen\":[");
        erste = true;
        for (TypeElement k : umsetzungen) {
            json.append(erste ? "" : ",").append(umsetzung(k, fehler));
            erste = false;
        }
        json.append("]}");
        try {
            FileObject f = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", VERZEICHNIS + modul + ".json");
            try (Writer w = f.openWriter()) {
                w.write(json.toString());
            }
        } catch (IOException ex) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Schnittstellen-Katalog nicht schreibbar: " + ex.getMessage());
        }
        return false;
    }

    /** Ausdrückliche Option, sonst: alle öffentlichen Schnittstellen genau in {@code *-interfaces}. */
    private boolean alleSchnittstellen(String modul) {
        String o = processingEnv.getOptions().get(OPTION_ALLE);
        if (o != null && !o.isBlank()) {
            return Boolean.parseBoolean(o.strip());
        }
        return modul != null && modul.endsWith("-interfaces");
    }

    /**
     * Öffentliche Schnittstellen (auch verschachtelte), keine Annotationstypen — in {@code *-interfaces}
     * alle, sonst nur mit {@code @ModulApi}. Dazu Typen mit {@code @ModulApiUmsetzung} an der Klasse
     * oder an einer Methode.
     */
    private void sammle(Element e) {
        if (e.getKind() == ElementKind.INTERFACE && e.getModifiers().contains(Modifier.PUBLIC)
                && (alle || annotation(e, MODUL_API).isPresent())) {
            gefunden.add((TypeElement) e);
        }
        if ((e.getKind() == ElementKind.CLASS || e.getKind() == ElementKind.RECORD || e.getKind() == ElementKind.ENUM)
                && beschriebeneUmsetzung(e)) {
            umsetzungen.add((TypeElement) e);
        }
        for (Element inner : e.getEnclosedElements()) {
            if (inner.getKind() == ElementKind.INTERFACE || inner.getKind() == ElementKind.CLASS || inner.getKind() == ElementKind.RECORD) {
                sammle(inner);
            }
        }
    }

    private String schnittstelle(TypeElement t, String zweck, String herkunft, Diagnostic.Kind fehler) {
        Optional<AnnotationMirror> api = annotation(t, MODUL_API);
        Map<String, String> werte = api.map(this::werte).orElse(Map.of());
        String art = werte.getOrDefault("art", "");
        if ("DTO".equals(art) && !istIName(t.getSimpleName().toString())) {
            processingEnv.getMessager().printMessage(fehler, "Modul-API (Karte 1422): " + t.getQualifiedName()
                    + " ist ein DTO und muss mit I beginnen (z. B. I" + t.getSimpleName() + ").", t);
        }
        if ("VERALTET".equals(werte.get("stabilitaet")) && werte.getOrDefault("ersatz", "").isBlank()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING, "Modul-API (Karte 1422): "
                    + t.getQualifiedName() + " ist veraltet, nennt aber keinen Ersatz (ersatz = …).", t);
        }
        StringBuilder s = new StringBuilder(JSON_NAME_AUF).append(Json.text(t.getQualifiedName().toString()))
                .append(",\"kurz\":").append(Json.text(t.getSimpleName().toString()))
                .append(",\"zweck\":").append(Json.text(zweck))
                .append(",\"annotiert\":").append(api.isPresent())
                .append(",\"art\":").append(Json.text(art))
                .append(",\"stabilitaet\":").append(Json.text(werte.getOrDefault("stabilitaet", "")))
                .append(",\"seit\":").append(Json.text(werte.getOrDefault("seit", "")))
                .append(",\"ersatz\":").append(Json.text(werte.getOrDefault("ersatz", "")))
                .append(",\"herkunft\":").append(Json.text(herkunft))
                .append(",\"erweitert\":[");
        boolean erste = true;
        for (var i : t.getInterfaces()) {
            s.append(erste ? "" : ",").append(Json.text(i.toString()));
            erste = false;
        }
        s.append("],\"methoden\":[");
        erste = true;
        for (Element m : t.getEnclosedElements()) {
            if (m.getKind() == ElementKind.METHOD && !m.getModifiers().contains(Modifier.PRIVATE)) {
                s.append(erste ? "" : ",");
                methode(s, (ExecutableElement) m);
                erste = false;
            }
        }
        return s.append("]}").toString();
    }

    /** Eine Methode als JSON-Objekt (Karte 1416, Sonar java:S3776: aus schnittstelle() herausgeloest). */
    private void methode(StringBuilder s, ExecutableElement x) {
        s.append(JSON_NAME_AUF).append(Json.text(x.getSimpleName().toString()))
                .append(",\"rueckgabe\":").append(Json.text(x.getReturnType().toString()))
                .append(",\"art\":").append(Json.text(art(x)))
                .append(",\"zweck\":").append(Json.text(javadoc(x)))
                .append(",\"parameter\":[");
        boolean ep = true;
        for (VariableElement p : x.getParameters()) {
            s.append(ep ? "" : ",").append(JSON_NAME_AUF).append(Json.text(p.getSimpleName().toString()))
                    .append(",\"typ\":").append(Json.text(p.asType().toString())).append('}');
            ep = false;
        }
        s.append("]}");
    }

    /** {@code IZeiteintrag}: I, dann ein Grossbuchstabe. */
    static boolean istIName(String kurz) {
        return kurz.length() > 1 && kurz.charAt(0) == 'I' && Character.isUpperCase(kurz.charAt(1));
    }

    private boolean beschriebeneUmsetzung(Element k) {
        if (annotation(k, MODUL_API_UMSETZUNG).isPresent()) {
            return true;
        }
        return k.getEnclosedElements().stream()
                .anyMatch(m -> m.getKind() == ElementKind.METHOD && annotation(m, MODUL_API_UMSETZUNG).isPresent());
    }

    /** Eine beschriebene Umsetzung: Klasse, umgesetzte Schnittstellen, Beschreibung, je Methode die eigene. */
    private String umsetzung(TypeElement k, Diagnostic.Kind fehler) {
        StringBuilder s = new StringBuilder("{\"klasse\":").append(Json.text(k.getQualifiedName().toString()))
                .append(",\"kurz\":").append(Json.text(k.getSimpleName().toString()))
                .append(",\"schnittstellen\":").append(Json.liste(new ArrayList<>(schnittstellenVon(k))));
        Optional<AnnotationMirror> a = annotation(k, MODUL_API_UMSETZUNG);
        beschreibung(s, a, k, fehler);
        s.append(",\"methoden\":[");
        boolean erste = true;
        for (Element m : k.getEnclosedElements()) {
            Optional<AnnotationMirror> am = m.getKind() == ElementKind.METHOD ? annotation(m, MODUL_API_UMSETZUNG) : Optional.empty();
            if (am.isPresent()) {
                s.append(erste ? "" : ",").append(JSON_NAME_AUF).append(Json.text(m.getSimpleName().toString()));
                beschreibung(s, am, m, fehler);
                s.append('}');
                erste = false;
            }
        }
        return s.append("]}").toString();
    }

    /** Die vier Pflichtangaben von {@code @ModulApiUmsetzung}; fehlt die Annotation, bleiben sie leer. */
    private void beschreibung(StringBuilder s, Optional<AnnotationMirror> a, Element wo, Diagnostic.Kind fehler) {
        Map<String, String> w = a.map(this::werte).orElse(Map.of());
        if (a.isPresent() && w.getOrDefault("beschreibung", "").isBlank()) {
            processingEnv.getMessager().printMessage(fehler,
                    "Modul-API (Karte 1422): @ModulApiUmsetzung braucht eine beschreibung.", wo);
        }
        s.append(",\"beschreibung\":").append(Json.text(w.getOrDefault("beschreibung", "")))
                .append(",\"seiteneffekte\":").append(Json.text(w.getOrDefault("seiteneffekte", "")))
                .append(",\"hinweise\":").append(Json.liste(a.map(x -> liste(x, "hinweise")).orElse(List.of())))
                .append(",\"beispiele\":").append(Json.liste(a.map(x -> liste(x, "beispiele")).orElse(List.of())));
    }

    /** Alle Schnittstellen in der Typ-Hierarchie, ohne {@code java.*}, sortiert. */
    private Set<String> schnittstellenVon(TypeElement k) {
        Set<String> raus = new java.util.TreeSet<>();
        sammleSchnittstellen(k.asType(), raus, new LinkedHashSet<>());
        return raus;
    }

    private void sammleSchnittstellen(TypeMirror t, Set<String> raus, Set<String> gesehen) {
        for (TypeMirror sup : processingEnv.getTypeUtils().directSupertypes(t)) {
            if (sup.getKind() != TypeKind.DECLARED) {
                continue;
            }
            TypeElement e = (TypeElement) ((DeclaredType) sup).asElement();
            String name = e.getQualifiedName().toString();
            if (!gesehen.add(name)) {
                continue;
            }
            if (e.getKind() == ElementKind.INTERFACE && !name.startsWith("java.")) {
                raus.add(name);
            }
            sammleSchnittstellen(sup, raus, gesehen);
        }
    }

    private static Optional<AnnotationMirror> annotation(Element e, String name) {
        for (AnnotationMirror m : e.getAnnotationMirrors()) {
            if (((TypeElement) m.getAnnotationType().asElement()).getQualifiedName().contentEquals(name)) {
                return Optional.of(m);
            }
        }
        return Optional.empty();
    }

    /** Einfache Werte (Text, Enum-Konstante) mit Vorgaben; Listen über {@link #liste}. */
    private Map<String, String> werte(AnnotationMirror m) {
        Map<String, String> w = new java.util.HashMap<>();
        processingEnv.getElementUtils().getElementValuesWithDefaults(m).forEach((k, v) -> {
            Object o = v.getValue();
            if (o instanceof VariableElement konstante) {
                w.put(k.getSimpleName().toString(), konstante.getSimpleName().toString());
            } else if (!(o instanceof List<?>)) {
                w.put(k.getSimpleName().toString(), String.valueOf(o));
            }
        });
        return w;
    }

    private List<String> liste(AnnotationMirror m, String element) {
        List<String> raus = new ArrayList<>();
        processingEnv.getElementUtils().getElementValuesWithDefaults(m).forEach((k, v) -> {
            if (k.getSimpleName().contentEquals(element) && v.getValue() instanceof List<?> l) {
                for (Object x : l) {
                    raus.add(String.valueOf(((AnnotationValue) x).getValue()));
                }
            }
        });
        return raus;
    }

    private String javadoc(Element e) {
        String d = processingEnv.getElementUtils().getDocComment(e);
        return d == null ? "" : d.strip();
    }

    /** Minimaler JSON-Text ohne Abhängigkeiten (der Prozessor läuft im Compiler). */
    static final class Json {
        private Json() {
        }

        static String text(String s) {
            StringBuilder b = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            b.append(String.format("\\u%04x", (int) c));
                        } else {
                            b.append(c);
                        }
                    }
                }
            }
            return b.append('"').toString();
        }

        static String liste(List<String> l) {
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                b.append(i == 0 ? "" : ",").append(text(l.get(i)));
            }
            return b.append(']').toString();
        }
    }

    /** static, default oder abstrakt (Karte 1416, Sonar java:S3358: kein verschachtelter Ternary). */
    private static String art(ExecutableElement x) {
        if (x.getModifiers().contains(Modifier.STATIC)) {
            return "static";
        }
        return x.getModifiers().contains(Modifier.DEFAULT) ? "default" : "abstrakt";
    }
}
