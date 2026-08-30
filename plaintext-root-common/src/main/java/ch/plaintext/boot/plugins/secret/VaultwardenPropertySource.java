/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.secret;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * {@link PropertySource} that sits IN FIRST POSITION in the environment and
 * transparently resolves {@code vault:} values from Vaultwarden (analogous to
 * Spring Cloud Vault).
 *
 * <p>{@link #getProperty(String)} asks the remaining property sources for the raw
 * value (its own source is skipped by identity). If the raw value starts with
 * {@code vault:}, it is resolved; otherwise {@code null} is returned so that the
 * normal resolver pass carries on to the real source (fall-through).</p>
 *
 * <p><b>Re-entrancy protection:</b> a {@link ThreadLocal} guard keeps the lazy
 * initialization of the vault client (which itself reads {@code plaintext.vault.*}
 * from the environment via {@link Binder} and therefore runs through this source
 * again) from tipping over into endless recursion: while a resolution is running,
 * this source immediately returns {@code null} for nested accesses.</p>
 */
class VaultwardenPropertySource extends PropertySource<Object> {

    /** Name of this source in the environment. */
    static final String SOURCE_NAME = "vaultwardenSecrets";

    /** Guard against re-entrancy during the lazy client initialization / resolution. */
    private static final ThreadLocal<Boolean> RESOLVING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final ConfigurableEnvironment environment;
    private final VaultwardenValueResolver resolver;

    VaultwardenPropertySource(ConfigurableEnvironment environment) {
        this(environment, new VaultwardenValueResolver(() -> buildService(environment)));
    }

    /** Test constructor with an injectable resolver (mocked service). */
    VaultwardenPropertySource(ConfigurableEnvironment environment, VaultwardenValueResolver resolver) {
        super(SOURCE_NAME);
        this.environment = environment;
        this.resolver = resolver;
    }

    @Override
    public Object getProperty(String name) {
        if (name == null || Boolean.TRUE.equals(RESOLVING.get())) {
            // Re-entrancy (e.g. a Binder access during the client init): do not resolve
            // again, the raw value is supposed to come from the real sources.
            return null;
        }
        RESOLVING.set(Boolean.TRUE);
        try {
            Object raw = rawFromOtherSources(name);
            if (!VaultwardenValueResolver.isVaultReference(raw)) {
                return null; // Fall-through: an ordinary value -> the real source takes over
            }
            return resolver.resolve(name, (String) raw);
        } finally {
            RESOLVING.remove();
        }
    }

    /**
     * This source introduces no keys of its own — it only transforms values of keys
     * that already exist in other sources. A {@code contains} check must therefore
     * NOT trigger a vault login: existence is answered by the real source.
     */
    @Override
    public boolean containsProperty(String name) {
        return false;
    }

    /** Raw value of the property from all remaining sources (its own skipped by identity). */
    private Object rawFromOtherSources(String name) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source == this) {
                continue;
            }
            Object value = source.getProperty(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Builds the {@link VaultwardenSecretService} free of Spring from the
     * {@code plaintext.vault.*} values of the environment (relaxed binding via
     * {@link Binder}). Only called during a running resolution (guard active), so
     * the binder reads the raw values from the real sources.
     */
    static VaultwardenSecretService buildService(ConfigurableEnvironment environment) {
        VaultwardenProperties props = Binder.get(environment)
                .bind("plaintext.vault", VaultwardenProperties.class)
                .orElseGet(VaultwardenProperties::new);
        String appName = environment.getProperty("spring.application.name", "plaintext");
        VaultwardenClient client = new VaultwardenClient(props, appName);
        return new VaultwardenSecretService(props, client);
    }
}
