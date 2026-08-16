/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import lombok.extern.slf4j.Slf4j;

/**
 * Loest alle {@code vault:}-Referenzen beim Start EINMAL auf und ersetzt sie in ihrer Quell-Source
 * durch den Klartext-Wert.
 *
 * <h2>Warum eager und nicht (nur) lazy</h2>
 * <p>Die {@link VaultwardenPropertySource} loest {@code vault:}-Werte beim Zugriff auf und bricht
 * bei einem Fehlschlag ab. Im echten Boot wird sie aber <b>umgangen</b>: Spring Boot ruft
 * {@code ConfigurationPropertySources.attach(environment)} auf und haengt damit eine Source
 * {@code configurationProperties} VOR sie, die Property-Zugriffe fortan selbst beantwortet. Der
 * Roh-Wert gewinnt, und der unaufgeloeste Literal {@code vault:<item>} landet im Ziel-Feld — bei
 * einem {@code String} unbemerkt, denn der Boot laeuft durch (Karte 868, gemessen an guild-INT).
 * Ein Secret-Feld, das den String {@code "vault:guild.remember-me-key"} enthaelt, ist die
 * schlimmste Sorte Ausfall: nichts schlaegt fehl, es ist nur alles wirkungslos.</p>
 *
 * <p>Nach dieser Ersetzung existiert kein {@code vault:}-Roh-Wert mehr, den ein Adapter
 * durchreichen koennte — der Wert ist ein gewoehnlicher String, unabhaengig davon, wer ihn liest.
 * Die lazy Source bleibt als zweite Linie fuer Sources, die sich nicht aufzaehlen lassen.</p>
 *
 * <h2>Fail-fast</h2>
 * <p>Ist eine Referenz nicht aufloesbar, fliegt die {@link VaultwardenPropertyResolutionException}
 * aus dem {@code EnvironmentPostProcessor} und der Start bricht ab — hier wirkt sie, weil kein
 * Spring-Adapter dazwischen liegt.</p>
 */
@Slf4j
final class VaultwardenEagerResolution {

    private VaultwardenEagerResolution() {
    }

    /**
     * @param environment Environment, dessen Sources durchsucht und ersetzt werden
     * @param resolver    Resolver fuer die eigentliche Aufloesung
     * @return Anzahl der ersetzten Referenzen
     */
    static int resolveAll(ConfigurableEnvironment environment, VaultwardenValueResolver resolver) {
        MutablePropertySources sources = environment.getPropertySources();
        int ersetzt = 0;

        // Kopie: wir ersetzen waehrend der Iteration Sources im selben MutablePropertySources.
        for (PropertySource<?> source : new ArrayList<>(sourcesAsList(sources))) {
            if (!(source instanceof EnumerablePropertySource<?> aufzaehlbar)) {
                continue;
            }
            Map<String, Object> aufgeloest = aufloesen(aufzaehlbar, resolver);
            if (aufgeloest.isEmpty()) {
                continue;
            }
            sources.replace(source.getName(), ersetzeIn(aufzaehlbar, aufgeloest));
            ersetzt += aufgeloest.size();
        }

        if (ersetzt > 0) {
            log.info("{} vault:-Referenz(en) beim Start aufgeloest", ersetzt);
        }
        return ersetzt;
    }

    /** Alle {@code vault:}-Werte der Source aufloesen (Schluessel bleibt unveraendert). */
    private static Map<String, Object> aufloesen(EnumerablePropertySource<?> source,
                                                 VaultwardenValueResolver resolver) {
        Map<String, Object> treffer = new LinkedHashMap<>();
        for (String key : source.getPropertyNames()) {
            if (istBootstrapSchluessel(key)) {
                // Der Vault-Zugang selbst darf nicht aus dem Vault kommen: der Client wird beim
                // Aufloesen aus genau diesen Werten gebaut, eine vault:-Referenz hier liefe im
                // Kreis. Solche Werte bleiben stehen (und fallen als Login-Fehler auf).
                continue;
            }
            Object raw = source.getProperty(key);
            if (VaultwardenValueResolver.isVaultReference(raw)) {
                // Fail-fast: wirft, wenn nicht aufloesbar — genau hier soll der Start abbrechen.
                treffer.put(key, resolver.resolve(key, (String) raw));
            }
        }
        return treffer;
    }

    /**
     * Baut eine Ersatz-Source <b>desselben Typs</b>. Der Typ traegt die Namens-Semantik: eine
     * {@link SystemEnvironmentPropertySource} bildet {@code plaintext.foo.bar} auf
     * {@code PLAINTEXT_FOO_BAR} ab. Eine gewoehnliche {@link MapPropertySource} kann das nicht —
     * wer hier den Typ verliert, macht die Referenz unauffindbar statt sie aufzuloesen.
     */
    @SuppressWarnings("unchecked")
    private static PropertySource<?> ersetzeIn(EnumerablePropertySource<?> original,
                                               Map<String, Object> aufgeloest) {
        Map<String, Object> kopie = new LinkedHashMap<>((Map<String, Object>) original.getSource());
        kopie.putAll(aufgeloest);
        if (original instanceof SystemEnvironmentPropertySource) {
            return new SystemEnvironmentPropertySource(original.getName(), kopie);
        }
        return new MapPropertySource(original.getName(), kopie);
    }

    /** {@code plaintext.vault.*} in beiden Schreibweisen (Property und Umgebungsvariable). */
    private static boolean istBootstrapSchluessel(String key) {
        String k = key.toLowerCase(java.util.Locale.ROOT).replace('_', '.');
        return k.startsWith("plaintext.vault.");
    }

    private static List<PropertySource<?>> sourcesAsList(MutablePropertySources sources) {
        List<PropertySource<?>> liste = new ArrayList<>();
        sources.forEach(liste::add);
        return liste;
    }
}
