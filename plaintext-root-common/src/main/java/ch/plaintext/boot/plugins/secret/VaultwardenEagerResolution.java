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
 * Resolves all {@code vault:} references ONCE at startup and replaces them in their source
 * property source with the plaintext value.
 *
 * <h2>Why eager and not (only) lazy</h2>
 * <p>The {@link VaultwardenPropertySource} resolves {@code vault:} values on access and aborts
 * on a failure. In a real boot it is <b>bypassed</b>, though: Spring Boot calls
 * {@code ConfigurationPropertySources.attach(environment)} and thereby hangs a source
 * {@code configurationProperties} IN FRONT of it, which answers property accesses itself from
 * then on. The raw value wins, and the unresolved literal {@code vault:<item>} ends up in the
 * target field — unnoticed with a {@code String}, because the boot runs through (Karte 868,
 * measured on guild INT). A secret field that contains the string
 * {@code "vault:guild.remember-me-key"} is the worst kind of failure: nothing fails, everything
 * is merely without effect.</p>
 *
 * <p>After this replacement no {@code vault:} raw value is left that an adapter could pass
 * through — the value is an ordinary string, no matter who reads it. The lazy source remains as
 * a second line of defence for sources that cannot be enumerated.</p>
 *
 * <h2>Fail-fast</h2>
 * <p>If a reference cannot be resolved, the {@link VaultwardenPropertyResolutionException} flies
 * out of the {@code EnvironmentPostProcessor} and startup aborts — here it takes effect, because
 * no Spring adapter sits in between.</p>
 */
@Slf4j
final class VaultwardenEagerResolution {

    private VaultwardenEagerResolution() {
    }

    /**
     * @param environment environment whose sources are scanned and replaced
     * @param resolver    resolver for the actual resolution
     * @return number of replaced references
     */
    static int resolveAll(ConfigurableEnvironment environment, VaultwardenValueResolver resolver) {
        MutablePropertySources sources = environment.getPropertySources();
        int ersetzt = 0;

        // Copy: during the iteration we replace sources in the very same MutablePropertySources.
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

    /** Resolve all {@code vault:} values of the source (the key stays unchanged). */
    private static Map<String, Object> aufloesen(EnumerablePropertySource<?> source,
                                                 VaultwardenValueResolver resolver) {
        Map<String, Object> treffer = new LinkedHashMap<>();
        for (String key : source.getPropertyNames()) {
            if (istBootstrapSchluessel(key)) {
                // The vault access itself must not come from the vault: the client is built from
                // exactly these values while resolving, so a vault: reference here would run in
                // circles. Such values are left standing (and show up as a login error).
                continue;
            }
            Object raw = source.getProperty(key);
            if (VaultwardenValueResolver.isVaultReference(raw)) {
                // Fail fast: throws when it cannot be resolved — this is exactly where startup should abort.
                treffer.put(key, resolver.resolve(key, (String) raw));
            }
        }
        return treffer;
    }

    /**
     * Builds a replacement source of the <b>same type</b>. The type carries the naming semantics: a
     * {@link SystemEnvironmentPropertySource} maps {@code plaintext.foo.bar} onto
     * {@code PLAINTEXT_FOO_BAR}. An ordinary {@link MapPropertySource} cannot do that — whoever
     * loses the type here makes the reference unfindable instead of resolving it.
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

    /** {@code plaintext.vault.*} in both spellings (property and environment variable). */
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
