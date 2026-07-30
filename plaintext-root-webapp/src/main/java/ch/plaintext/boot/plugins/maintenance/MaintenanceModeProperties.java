/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.maintenance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for maintenance mode (see {@link MaintenanceModeFilter}).
 *
 * <p>Properties live under {@code plaintext.maintenance} (env:
 * {@code PLAINTEXT_MAINTENANCE_*}). All values are runtime-mutable through
 * Spring's {@code @RefreshScope} or by setting them in
 * {@code application.yml} and restarting.
 */
@Component
@ConfigurationProperties(prefix = "plaintext.maintenance")
@Data
public class MaintenanceModeProperties {

    /**
     * Master switch. When {@code false} the filter is a no-op.
     */
    private boolean enabled = false;

    /**
     * Plain-text message rendered on the maintenance page. HTML is escaped.
     */
    private String message =
            "We're performing scheduled maintenance and will be back shortly.";

    /**
     * Roles whose holders bypass the maintenance page entirely. Compared
     * against {@code Authentication#getAuthorities()} so the values must be
     * the full authority strings (typically prefixed {@code ROLE_}).
     */
    private List<String> bypassRoles = new ArrayList<>(List.of(
            "ROLE_ADMIN", "ROLE_ROOT"));

    /**
     * Path prefixes that are served as usual even while maintenance mode is
     * on. The default list keeps the login flow, JSF resources, and the
     * actuator health check working so admins can still authenticate and
     * monitoring can still confirm liveness.
     */
    private List<String> alwaysAllowedPathPrefixes = new ArrayList<>(List.of(
            "/login",
            "/logout",
            "/actuator/health",
            "/jakarta.faces.resource",
            "/javax.faces.resource",
            "/static/",
            "/css/",
            "/js/",
            "/images/",
            "/webjars/"));

    /**
     * Value of the {@code Retry-After} header on the 503 response, in
     * seconds. Default: 30 minutes.
     */
    private long retryAfterSeconds = 1800;
}
