/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

import java.util.Optional;

/**
 * Reads a secret maintained via the <i>Root → Secrets</i> page <b>at runtime</b>, no matter which
 * backend holds it.
 *
 * <p>This interface exists because the {@code vault:} prefix in properties cannot do that. That
 * prefix is resolved by an {@code EnvironmentPostProcessor} at startup and reads Vaultwarden and
 * nothing else. For the {@code LOCAL_DB} backend that route cannot be retrofitted, for two
 * independent reasons: at startup there is neither a connected database nor a tenant, and secrets
 * are tenant-bound. A {@code secret:} prefix analogous to {@code vault:} is therefore impossible
 * in principle — the resolution has to happen in the request context.
 *
 * <p>The pleasant side effect: a change in the secrets management takes effect immediately,
 * whereas a {@code vault:} property keeps the old value until the next restart.
 *
 * <p><b>Not for the UI.</b> The secrets management is deliberately one-way — values are set, never
 * displayed. This interface returns cleartext for technical consumers (such as inserting
 * credentials into a script generated for download) and does not belong on a page.
 *
 * <p>It is implemented by the {@code SecretService} in {@code plaintext-admin-secrets}. Consumers
 * should keep the dependency <b>optional</b> ({@code @Autowired(required = false)}), because not
 * every application includes the secrets module.
 */
public interface SecretResolver {

    /**
     * Cleartext value of the secret in the current tenant.
     *
     * @param name name of the secret as shown in the secrets management (e.g.
     *             {@code zeiterfassung.jira-password})
     * @return the value, or {@link Optional#empty()} if no secret of that name exists, it has been
     *         deleted, or the backend holding it is currently unreachable. An empty result is
     *         <b>not an error</b> — callers should provide a fallback for it (typically the
     *         previous property) instead of failing.
     */
    Optional<String> resolve(String name);
}
