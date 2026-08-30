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
 * Feeds {@code plaintext-defaults.yml} in as the <b>lowest</b> property source and thereby makes
 * the shared base settings take effect in every application.
 *
 * <h2>The problem this processor solves (Karte 620)</h2>
 * <p>Spring Boot loads exactly <b>one</b> {@code classpath:/application.yml}. If an application
 * brings one of its own — and all five consumers do — the version from
 * {@code plaintext-root-webapp} is entirely invisible. Not partially, not merged: invisible.
 * On 08.08.2026 14 keys were lost that way, among them {@code same-site},
 * {@code tracking-modes} and the session timeout.
 *
 * <p>The treacherous part is not the loss but its invisibility: the setting is in the repository,
 * it has been reviewed, it looks as if it were set — and it takes effect nowhere. Two cases have
 * been found so far, both by accident (this one and {@code @EnableMethodSecurity}, Karte 546),
 * and both only through a measurement on the running system.
 *
 * <h2>Why an EnvironmentPostProcessor</h2>
 * <p>The alternative would be to add {@code spring.config.import} to every application. That only
 * works as long as nobody forgets it — and a forgotten import is just as unnoticeable as the
 * problem it is supposed to fix. This processor needs nothing from the application: it is found
 * via {@code META-INF/spring.factories} as soon as {@code plaintext-root-common} is on the
 * classpath.
 *
 * <h2>Precedence: deliberately the lowest</h2>
 * <p>{@link MutablePropertySources#addLast} — base settings, not mandates. Every
 * {@code application.yml}, every profile, every environment variable and every command line
 * argument still wins. That is the difference to the
 * {@code VaultwardenEnvironmentPostProcessor}, which works with {@code addFirst}: secrets
 * <em>are supposed</em> to beat everything, defaults are not.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@Slf4j
public class PlaintextDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Name of the injected property source — also the marker for the idempotency check. */
    public static final String SOURCE_NAME = "plaintext-defaults";

    private static final String DATEI = "plaintext-defaults.yml";

    private final YamlPropertySourceLoader lader = new YamlPropertySourceLoader();

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        MutablePropertySources sources = environment.getPropertySources();
        if (sources.contains(SOURCE_NAME)) {
            return; // idempotent — the processor may be run more than once
        }

        Resource resource = new ClassPathResource(DATEI);
        if (!resource.exists()) {
            // Not an error: root-common can be embedded without the file as well.
            log.debug("{} nicht im Classpath — keine gemeinsamen Grundeinstellungen geladen", DATEI);
            return;
        }

        try {
            List<PropertySource<?>> geladen = lader.load(SOURCE_NAME, resource);
            // Insert in reverse: load() returns the YAML documents in reading order, which
            // addLast would otherwise turn around.
            for (int i = geladen.size() - 1; i >= 0; i--) {
                sources.addLast(geladen.get(i));
            }
            log.debug("{} als unterste Property-Source eingespeist ({} Dokument(e))",
                    DATEI, geladen.size());
        } catch (IOException e) {
            // Fail fast: an unreadable defaults file is a configuration error. Silently skipping
            // it brought back exactly the situation this processor is meant to fix —
            // settings that look present and take no effect.
            throw new IllegalStateException(
                    DATEI + " ist im Classpath, laesst sich aber nicht lesen", e);
        }
    }

    /**
     * <b>Run as late as possible</b> — and that is not a nicety here, it is the whole point.
     *
     * <p>The {@code ConfigDataEnvironmentPostProcessor} that loads the {@code application.yml} runs
     * at {@code HIGHEST_PRECEDENCE + 10}. When this processor ran before it, the source we insert
     * with {@code addLast} ended up <em>above</em> the {@code application.yml} that was only added
     * afterwards — and the base settings overrode precisely what they were meant to complement.
     *
     * <p>That happened on PROD on 08.08.2026: {@code plaintext-root} lost its {@code Secure} cookie,
     * because the default {@code false} from {@code plaintext-defaults.yml} beat the value
     * {@code ${PLAINTEXT_COOKIE_SECURE:true}} from its own {@code application.yml}. Measured on the
     * Set-Cookie header before and after the rollout of 1.532.0.
     *
     * <p>With {@code LOWEST_PRECEDENCE} the processor runs after ConfigData, and {@code addLast}
     * puts the source where it belongs: below everything else. The same reasoning as in the
     * {@code VaultwardenEnvironmentPostProcessor}, only with the opposite goal — running late means
     * "right at the top" with {@code addFirst} and "right at the bottom" with {@code addLast}.
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
