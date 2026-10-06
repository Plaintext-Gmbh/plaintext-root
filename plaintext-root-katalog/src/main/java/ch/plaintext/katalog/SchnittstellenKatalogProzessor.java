/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.katalog;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 * {@code plaintext.katalog.streng} (Vorgabe {@code true}).</p>
 */
@SupportedAnnotationTypes("*")
@SupportedOptions({SchnittstellenKatalogProzessor.OPTION_MODUL, SchnittstellenKatalogProzessor.OPTION_STRENG})
public class SchnittstellenKatalogProzessor extends AbstractProcessor {

    /** Beginn eines Eintrags mit Name (Karte 1416, Sonar java:S1192). */
    private static final String JSON_NAME_AUF = "{\"name\":";

    public static final String OPTION_MODUL = "plaintext.katalog.modul";
    public static final String OPTION_STRENG = "plaintext.katalog.streng";
    public static final String VERZEICHNIS = "META-INF/plaintext-katalog/";

    private final List<TypeElement> gefunden = new ArrayList<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment env) {
        if (!env.processingOver()) {
            for (Element e : env.getRootElements()) {
                sammle(e);
            }
            return false;
        }
        String modul = processingEnv.getOptions().get(OPTION_MODUL);
        if (modul == null || modul.isBlank()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Schnittstellen-Katalog: Option -A" + OPTION_MODUL + " fehlt, kein Katalog geschrieben.");
            return false;
        }
        boolean streng = !"false".equalsIgnoreCase(processingEnv.getOptions().get(OPTION_STRENG));
        gefunden.sort(Comparator.comparing(t -> t.getQualifiedName().toString()));
        StringBuilder json = new StringBuilder("{\"modul\":").append(Json.text(modul)).append(",\"schnittstellen\":[");
        boolean erste = true;
        for (TypeElement t : gefunden) {
            String zweck = javadoc(t);
            if (zweck.isEmpty()) {
                processingEnv.getMessager().printMessage(streng ? Diagnostic.Kind.ERROR : Diagnostic.Kind.WARNING,
                        "Schnittstellen-Katalog (Karte 1405): " + t.getQualifiedName()
                                + " braucht ein Javadoc, das den Zweck beschreibt.", t);
            }
            json.append(erste ? "" : ",").append(schnittstelle(t, zweck));
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

    /** Öffentliche Schnittstellen (auch verschachtelte), keine Annotationstypen. */
    private void sammle(Element e) {
        if (e.getKind() == ElementKind.INTERFACE && e.getModifiers().contains(Modifier.PUBLIC)) {
            gefunden.add((TypeElement) e);
        }
        for (Element inner : e.getEnclosedElements()) {
            if (inner.getKind() == ElementKind.INTERFACE || inner.getKind() == ElementKind.CLASS || inner.getKind() == ElementKind.RECORD) {
                sammle(inner);
            }
        }
    }

    private String schnittstelle(TypeElement t, String zweck) {
        StringBuilder s = new StringBuilder(JSON_NAME_AUF).append(Json.text(t.getQualifiedName().toString()))
                .append(",\"kurz\":").append(Json.text(t.getSimpleName().toString()))
                .append(",\"zweck\":").append(Json.text(zweck))
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
    }

    /** static, default oder abstrakt (Karte 1416, Sonar java:S3358: kein verschachtelter Ternary). */
    private static String art(ExecutableElement x) {
        if (x.getModifiers().contains(Modifier.STATIC)) {
            return "static";
        }
        return x.getModifiers().contains(Modifier.DEFAULT) ? "default" : "abstrakt";
    }
}
