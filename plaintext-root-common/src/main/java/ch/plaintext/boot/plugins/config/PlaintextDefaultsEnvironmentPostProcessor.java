/*
 * Plaintext GmbH
 */
package ch.plaintext.boot.plugins.config;

import java.io.IOException;
import java.util.List;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import lombok.extern.slf4j.Slf4j;

/**
 * Speist {@code plaintext-defaults.yml} als <b>unterste</b> Property-Source ein und macht damit
 * die gemeinsamen Grundeinstellungen in jeder Anwendung wirksam.
 *
 * <h2>Das Problem, das dieser Processor loest (Karte 620)</h2>
 * <p>Spring Boot laedt genau <b>eine</b> {@code classpath:/application.yml}. Bringt eine
 * Anwendung eine eigene mit — und das tun alle fuenf Consumer —, ist die Fassung aus
 * {@code plaintext-root-webapp} vollstaendig unsichtbar. Nicht teilweise, nicht zusammengefuehrt:
 * unsichtbar. Am 08.08.2026 gingen auf diesem Weg 14 Schluessel verloren, darunter
 * {@code same-site}, {@code tracking-modes} und das Session-Timeout.
 *
 * <p>Das Tueckische daran ist nicht der Verlust, sondern seine Unsichtbarkeit: Die Einstellung
 * steht im Repository, sie ist reviewt, sie sieht gesetzt aus — und sie wirkt nirgends. Zwei
 * Faelle wurden bisher zufaellig gefunden (dieser und {@code @EnableMethodSecurity}, Karte 546),
 * beide erst durch eine Messung am laufenden System.
 *
 * <h2>Warum ein EnvironmentPostProcessor</h2>
 * <p>Die Alternative waere, in jede Anwendung {@code spring.config.import} einzutragen. Das
 * wirkt nur, solange es niemand vergisst — und ein vergessener Import faellt genauso wenig auf
 * wie das Problem, das er beheben soll. Dieser Processor braucht kein Zutun der Anwendung: Er
 * wird ueber {@code META-INF/spring.factories} gefunden, sobald {@code plaintext-root-common}
 * im Classpath liegt.
 *
 * <h2>Praezedenz: bewusst die niedrigste</h2>
 * <p>{@link MutablePropertySources#addLast} — Grundeinstellungen, keine Vorschriften. Jede
 * {@code application.yml}, jedes Profil, jede Umgebungsvariable und jedes Kommandozeilen-
 * Argument gewinnt weiterhin. Das ist der Unterschied zum
 * {@code VaultwardenEnvironmentPostProcessor}, der mit {@code addFirst} arbeitet: Secrets
 * <em>sollen</em> alles schlagen, Defaults nicht.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
public class PlaintextDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Name der eingespeisten Property-Source — auch die Kennung fuer die Idempotenz-Pruefung. */
    public static final String SOURCE_NAME = "plaintext-defaults";

    private static final String DATEI = "plaintext-defaults.yml";

    private final YamlPropertySourceLoader lader = new YamlPropertySourceLoader();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(SOURCE_NAME)) {
            return; // idempotent — der Processor kann mehrfach durchlaufen werden
        }

        Resource resource = new ClassPathResource(DATEI);
        if (!resource.exists()) {
            // Kein Fehlerfall: root-common laesst sich auch ohne die Datei einbinden.
            log.debug("{} nicht im Classpath — keine gemeinsamen Grundeinstellungen geladen", DATEI);
            return;
        }

        try {
            List<PropertySource<?>> geladen = lader.load(SOURCE_NAME, resource);
            // Rueckwaerts einfuegen: load() liefert die YAML-Dokumente in Lesereihenfolge,
            // addLast kehrt sie sonst um.
            for (int i = geladen.size() - 1; i >= 0; i--) {
                sources.addLast(geladen.get(i));
            }
            log.debug("{} als unterste Property-Source eingespeist ({} Dokument(e))",
                    DATEI, geladen.size());
        } catch (IOException e) {
            // Fail-fast: Eine unlesbare Defaults-Datei ist ein Konfigurationsfehler. Sie still
            // zu ueberspringen brachte genau die Situation zurueck, die dieser Processor
            // beheben soll — Einstellungen, die vorhanden aussehen und nicht wirken.
            throw new IllegalStateException(
                    DATEI + " ist im Classpath, laesst sich aber nicht lesen", e);
        }
    }

    /**
     * <b>Moeglichst spaet ausfuehren</b> — und das ist hier keine Feinheit, sondern der Kern.
     *
     * <p>Der {@code ConfigDataEnvironmentPostProcessor}, der die {@code application.yml} laedt,
     * laeuft bei {@code HIGHEST_PRECEDENCE + 10}. Lief dieser Processor davor, landete unsere
     * mit {@code addLast} eingefuegte Source <em>ueber</em> der erst danach eingefuegten
     * {@code application.yml} — und die Grundeinstellungen ueberschrieben genau das, was sie
     * ergaenzen sollten.
     *
     * <p>Das ist am 08.08.2026 auf PROD passiert: {@code plaintext-root} verlor sein
     * {@code Secure}-Cookie, weil der Default {@code false} aus {@code plaintext-defaults.yml}
     * den Wert {@code ${PLAINTEXT_COOKIE_SECURE:true}} aus der eigenen {@code application.yml}
     * schlug. Gemessen am Set-Cookie-Header vor und nach dem Rollout von 1.532.0.
     *
     * <p>Mit {@code LOWEST_PRECEDENCE} laeuft der Processor nach ConfigData, und {@code addLast}
     * setzt die Source dorthin, wo sie hingehoert: unter alles andere. Dieselbe Ueberlegung wie
     * beim {@code VaultwardenEnvironmentPostProcessor}, nur mit umgekehrtem Ziel — spaet laufen
     * heisst bei {@code addFirst} "ganz oben" und bei {@code addLast} "ganz unten".
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
