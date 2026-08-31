/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration of the page access guard ({@link PageAccessGuardService} /
 * {@link PageAccessGuardFilter}).
 *
 * <p><b>Why the default is {@link PageGuardMode#REPORT}:</b> the framework is consumed by several
 * apps that bring their own views and their own {@code @MenuAnnotation}s. An immediate
 * {@link PageGuardMode#STRICT} would lock out every view without a menu entry there.
 * {@code REPORT} enforces all the other tightenings (canonical link comparison, {@code catch} ->
 * deny, allowlist, aliases), but lets views without an assignment through with a WARN message — so
 * every app first gets its list of gaps into the log and can then switch to {@code STRICT}
 * deliberately. The root app itself runs in {@code STRICT} (set in its {@code application.yml}).
 *
 * <p><b>Prefix.</b> Up to 1.491.0 this class was the inner class
 * {@code PlaintextSecurityProperties.PageGuardProperties} and bound to the field {@code pageGuard}
 * under {@code plaintext.security}. The effective prefix
 * {@code plaintext.security.page-guard} has stayed unchanged when it was extracted —
 * existing {@code application.yml} files and environment variables keep working. Only whoever
 * referenced the Java class directly has to adjust the import.
 *
 * @author plaintext.ch
 * @since 1.492.0
 */
@ConfigurationProperties(prefix = "plaintext.security.page-guard")
@Data
public class PageGuardProperties {

    /**
     * Emergency off. With {@code false} neither the filter nor the {@code preRenderView} guard
     * checks anything (the Spring Security rules in {@code PlaintextSecurityConfig} are not
     * affected by this). Intended only for the case where the guard blocks legitimate pages in
     * PROD and no rollback is possible.
     */
    private boolean enabled = true;

    /**
     * Behaviour for views without a menu assignment, and parent role inheritance.
     * See {@link PageGuardMode}.
     */
    private PageGuardMode mode = PageGuardMode.REPORT;

    /**
     * Additional views that are always reachable (on top of the framework defaults in
     * {@link PageAccessGuardService}). Extension and leading slash do not matter
     * ({@code /myview.xhtml} == {@code myview.html} == {@code myview}). An entry ending in
     * {@code /**} acts as a prefix ({@code nosec/**}).
     */
    private List<String> allowlist = new ArrayList<>();

    /**
     * View aliases: "guard this view like this menu link". The key is the view, the value the
     * menu link whose role/tenant visibility should apply. For detail pages without a menu entry
     * of their own, e.g.
     * {@code rechnungdetail.xhtml: rechnungen.html}. Both sides are canonicalized, so the
     * extension does not matter.
     */
    private Map<String, String> aliases = new LinkedHashMap<>();

    /**
     * Whether {@link PageAccessGuardStartupReport} scans the shipped facelets at startup and
     * reports those without an access rule. The scan reads every view once; with a very large
     * number of views, or in tests, it can be switched off with this.
     */
    private boolean startupReport = true;
}
