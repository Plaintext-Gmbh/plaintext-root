/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Meldet beim Start, welche ausgelieferten JSF-Views der Seiten-Zugriffsschutz nicht zuordnen kann
 * (Karte 308).
 *
 * <p>Motivation: Der Guard ist ein Framework-Baustein, die Views kommen aus den konsumierenden
 * Apps. Beim Umstieg auf {@link PageGuardMode#STRICT} wuerde jede View ohne Menueeintrag, Alias
 * oder Allowlist-Eintrag gesperrt. Statt das durch Benutzer-Fehlermeldungen herauszufinden, listet
 * dieser Report die Luecken beim Boot auf:
 * <ul>
 *   <li>Modus {@link PageGuardMode#REPORT}: WARN mit dem Hinweis, dass diese Views nach dem Umstieg
 *       gesperrt wuerden.</li>
 *   <li>Modus {@link PageGuardMode#STRICT}: WARN, dass diese Views jetzt gesperrt SIND.</li>
 * </ul>
 *
 * <p>Fragmente ({@code includes/}, {@code templates/}) werden ausgenommen: sie werden nur per
 * {@code ui:include} bzw. als {@code template=} eingebunden und sind keine aufrufbaren Seiten.
 * Dass sie bei Direktaufruf gesperrt werden, ist gewollt.
 */
@Component
@Slf4j
public class PageAccessGuardStartupReport {

    private static final String[] MUSTER = {
            "classpath*:META-INF/resources/**/*.xhtml"
    };

    /** Pfadbestandteile, die ein Facelet als Fragment/Template kennzeichnen. */
    private static final List<String> FRAGMENT_MARKER = List.of("/includes/", "/templates/");

    /**
     * Ein {@code <ui:composition>} OHNE {@code template=} ist ein Include-Fragment (wird per
     * {@code ui:include} eingebunden, z.B. {@code menu.xhtml}) und keine aufrufbare Seite.
     */
    private static final Pattern UI_COMPOSITION = Pattern.compile("<ui:composition\\b([^>]*)>", Pattern.DOTALL);

    private final ResourcePatternResolver resourcePatternResolver;
    private final PageAccessGuardService pageAccessGuardService;

    public PageAccessGuardStartupReport(ResourcePatternResolver resourcePatternResolver,
                                        PageAccessGuardService pageAccessGuardService) {
        this.resourcePatternResolver = resourcePatternResolver;
        this.pageAccessGuardService = pageAccessGuardService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void berichte() {
        if (!pageAccessGuardService.isEnabled()) {
            log.warn("PageAccessGuard is DISABLED (plaintext.security.page-guard.enabled=false) — "
                    + "no page-level authorization is enforced. This is an emergency switch, not a setting.");
            return;
        }

        List<String> views = sammleViews();
        if (views.isEmpty()) {
            log.debug("PageAccessGuard startup report: no JSF views found under META-INF/resources");
            return;
        }

        List<String> ohneZuordnung = new ArrayList<>();
        for (String view : views) {
            if (!pageAccessGuardService.istZugeordnet(view)) {
                ohneZuordnung.add(view);
            }
        }

        log.info("PageAccessGuard startup report: mode={}, {} views scanned, {} without menu entry/alias/allowlist",
                pageAccessGuardService.getMode(), views.size(), ohneZuordnung.size());

        if (ohneZuordnung.isEmpty()) {
            return;
        }
        if (pageAccessGuardService.getMode() == PageGuardMode.STRICT) {
            log.warn("PageAccessGuard (mode=STRICT): these {} views are BLOCKED for everyone — add "
                            + "@MenuAnnotation(link=\"...html\"), or configure "
                            + "plaintext.security.page-guard.aliases / .allowlist: {}",
                    ohneZuordnung.size(), ohneZuordnung);
        } else {
            log.warn("PageAccessGuard (mode=REPORT): these {} views have NO access rule and are "
                            + "currently reachable by every logged-in user. They would be blocked by "
                            + "mode=STRICT — add @MenuAnnotation(link=\"...html\"), or configure "
                            + "plaintext.security.page-guard.aliases / .allowlist: {}",
                    ohneZuordnung.size(), ohneZuordnung);
        }
    }

    /** Alle ausgelieferten Views (ohne Fragmente), als Pfad relativ zu {@code META-INF/resources}. */
    private List<String> sammleViews() {
        TreeSet<String> ergebnis = new TreeSet<>();
        for (String muster : MUSTER) {
            try {
                for (Resource resource : resourcePatternResolver.getResources(muster)) {
                    String pfad = relativerPfad(resource);
                    if (pfad == null || istFragment(pfad) || istIncludeFragment(resource)) {
                        continue;
                    }
                    ergebnis.add(pfad);
                }
            } catch (IOException e) {
                log.warn("PageAccessGuard startup report: cannot scan {}: {}", muster, e.getMessage());
            }
        }
        return new ArrayList<>(ergebnis);
    }

    private String relativerPfad(Resource resource) {
        String url;
        try {
            url = resource.getURL().toString();
        } catch (IOException e) {
            return null;
        }
        int index = url.lastIndexOf("META-INF/resources/");
        if (index < 0) {
            return null;
        }
        return "/" + url.substring(index + "META-INF/resources/".length());
    }

    private boolean istFragment(String pfad) {
        for (String marker : FRAGMENT_MARKER) {
            if (pfad.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private boolean istIncludeFragment(Resource resource) {
        try (InputStream stream = resource.getInputStream()) {
            String inhalt = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            Matcher composition = UI_COMPOSITION.matcher(inhalt);
            return composition.find() && !composition.group(1).contains("template=");
        } catch (IOException e) {
            log.debug("PageAccessGuard startup report: cannot read {}: {}", resource, e.getMessage());
            return false;
        }
    }
}
