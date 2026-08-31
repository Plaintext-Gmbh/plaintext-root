/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reports at startup which of the shipped JSF views the page access guard cannot assign to a rule
 * (card 308).
 *
 * <p>Motivation: the guard is a framework building block, the views come from the consuming apps.
 * When switching to {@link PageGuardMode#STRICT}, every view without a menu entry, alias or
 * allowlist entry would be blocked. Instead of finding that out through user error reports, this
 * report lists the gaps at boot time:
 * <ul>
 *   <li>Mode {@link PageGuardMode#REPORT}: WARN with the note that these views would be blocked
 *       after the switch.</li>
 *   <li>Mode {@link PageGuardMode#STRICT}: WARN that these views ARE blocked now.</li>
 * </ul>
 *
 * <p>Fragments ({@code includes/}, {@code templates/}) are excluded: they are only pulled in via
 * {@code ui:include} resp. as {@code template=} and are not callable pages. That they are blocked
 * on a direct call is intentional.
 */
@Slf4j
public class PageAccessGuardStartupReport {

    private static final String[] MUSTER = {
            "classpath*:META-INF/resources/**/*.xhtml"
    };

    /** Path components that mark a facelet as a fragment/template. */
    private static final List<String> FRAGMENT_MARKER = List.of("/includes/", "/templates/");

    /**
     * A {@code <ui:composition>} WITHOUT {@code template=} is an include fragment (pulled in via
     * {@code ui:include}, e.g. {@code menu.xhtml}) and not a callable page.
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

    /** All shipped views (without fragments), as a path relative to {@code META-INF/resources}. */
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
