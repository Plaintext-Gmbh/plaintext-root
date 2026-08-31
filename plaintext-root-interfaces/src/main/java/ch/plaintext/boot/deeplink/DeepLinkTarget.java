/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

/**
 * SPI for deep link targets (Card 345).
 *
 * <p>A module that wants to link directly to a specific record from a mail (or from anywhere
 * else) registers a Spring bean implementing this interface. The root mechanism
 * ({@code /deeplink}) then takes care of:
 * <ol>
 *   <li>enforcing login (and carrying the deep link through the login flow),</li>
 *   <li>checking whether the user has access to the target tenant at all,</li>
 *   <li>switching to the target tenant,</li>
 *   <li>asking {@link #isAccessible(String, String)} — the <em>server-side</em> check of whether
 *       the user may see this particular record,</li>
 *   <li>forwarding to {@link #getView()} and passing the id along as a view parameter.</li>
 * </ol>
 *
 * <h2>Security contract (please read carefully)</h2>
 * <ul>
 *   <li>A deep link <b>grants no permission</b>. It is only a navigation aid. The target page
 *       still has to load its record with tenant separation itself — the check here does not
 *       replace that, it only prevents the link from leading there in the first place.</li>
 *   <li>{@link #isAccessible(String, String)} has to be <b>fail-closed</b>: when in doubt,
 *       {@code false}. If the method throws, the root mechanism treats that as a rejection.</li>
 *   <li>The method has to protect against <b>guessed ids</b>: checking that the record exists is
 *       not enough — it has to belong to the given tenant (and, where the module has fine-grained
 *       permissions, be visible to the current user).</li>
 *   <li>{@link #getView()} comes <b>from the server</b>, never from the URL. That way a tampered
 *       link cannot target an arbitrary destination (no open redirect).</li>
 * </ul>
 */
public interface DeepLinkTarget {

    /**
     * Stable, technical key of the target, as it appears in the link as {@code type=} —
     * e.g. {@code "auszahlung"}. Lowercase letters, digits, {@code -} and {@code _} only;
     * other values are rejected at startup.
     */
    String getType();

    /**
     * Target view to forward to — the same value as in a {@code @MenuAnnotation(link=...)}, so
     * e.g. {@code "auszahlungen.html"}. Defined on the server side, never taken from the URL.
     */
    String getView();

    /** Human-readable name for the root overview, e.g. "Auszahlung". */
    String getLabel();

    /** Name of the view parameter under which the id is appended to the target page. */
    default String getParamName() {
        return "id";
    }

    /**
     * <b>The server-side access check.</b> May the currently logged-in user see this record in
     * this tenant?
     *
     * <p>Called <em>after</em> the switch to the target tenant and before the forward — the
     * modules filter their data by the active tenant, so a check made before the switch would
     * systematically return {@code false} (see {@code DeepLinkResolver}). The switch only ever
     * goes to a tenant the user would be allowed to select anyway; if this check comes back
     * negative, the previous tenant is restored. The tenant is additionally passed in explicitly
     * so that the implementation can check against it instead of relying on the session state
     * alone.
     *
     * @param mandat target tenant, lowercase; already checked for "the user has access to it"
     * @param id     record id from the link; already validated against a narrow character pattern,
     *               but unchecked in substance (may be guessed or tampered with)
     * @return {@code true} only if the record exists, belongs to {@code mandat} and is visible to
     *         the current user
     */
    boolean isAccessible(String mandat, String id);
}
