/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.secrets;

/**
 * Abstraction over the secret backends. Deliberately <b>one-way</b>: the values themselves are NOT
 * read out through this interface (no {@code get(value)}) — only metadata is shown and set. Listing
 * and managing the managed secrets is done by the {@link SecretService} via {@code secret_entry}
 * (which also works for backends without a list API, such as Vaultwarden).
 */
public interface SecretBackend {

    SecretBackendType type();

    /** Is this backend configured/reachable? */
    boolean isAvailable();

    /**
     * Live test: is the backend working right now, and if not — what is missing? Performs (where it
     * makes sense) a real reachability check, not just config presence. For the UI in the backend area.
     */
    SecretHealth health();

    /** Comment/metadata of a secret (e.g. Vaultwarden note), or {@code null}. */
    String comment(String name);

    /** Set/create a secret (one-way). {@code note} = optional free-text note. */
    void set(String name, String value, String note);

    /**
     * INTERNAL — reads the plaintext value of a secret. NEVER for the UI; the one-way character of the
     * display is preserved. {@code null} if it is not readable.
     *
     * <p>Permitted consumers are the migration (moving backend→backend) and
     * {@link SecretService#resolve(String)}, through which technical consumers obtain a managed secret
     * at runtime.
     */
    String readValue(String name);
}
