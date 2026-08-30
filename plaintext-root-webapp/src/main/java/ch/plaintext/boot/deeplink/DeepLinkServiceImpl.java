/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.deeplink;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registry + link building for deep links (card 345).
 *
 * <p>The registry arises purely from the {@link DeepLinkTarget} beans present in the context; a
 * module "registers" by providing such a bean. Nothing is persisted —
 * deep links are pure addresses, not tokens. Exactly for that reason they also grant no
 * permission: there is no secret that could be presented, every call is checked freshly against the
 * permissions of the logged-in user when it is opened.
 */
@Service
@Slf4j
public class DeepLinkServiceImpl implements DeepLinkService {

    private final Map<String, DeepLinkTarget> targets = new LinkedHashMap<>();
    private final String baseUrl;

    public DeepLinkServiceImpl(List<DeepLinkTarget> registrierteZiele,
                               @Value("${plaintext.baseurl:http://localhost:8080}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : ohneEndSchraegstriche(baseUrl);
        List<DeepLinkTarget> sortiert = new ArrayList<>(registrierteZiele);
        sortiert.sort(Comparator.comparing(t -> String.valueOf(t.getType())));
        for (DeepLinkTarget ziel : sortiert) {
            registriere(ziel);
        }
        log.info("Deep-Link-Registry: {} Ziel(e) registriert: {}", targets.size(), targets.keySet());
    }

    /**
     * Fail-fast at startup instead of a silent gap at runtime: a target with an unclean
     * {@code type} or without a view would be rejected on call anyway — then better make it
     * visible right away. Duplicate types are rejected, because it would otherwise be unclear which
     * module (and thus which access check) is responsible for a link.
     */
    private void registriere(DeepLinkTarget ziel) {
        String type = ziel.getType() == null ? null : ziel.getType().toLowerCase(Locale.ROOT);
        if (!DeepLinkFormat.istGueltigerType(type)) {
            throw new IllegalStateException("Deep-Link-Ziel " + ziel.getClass().getName()
                    + " hat einen ungueltigen type: '" + ziel.getType() + "'");
        }
        if (ziel.getView() == null || ziel.getView().isBlank() || ziel.getView().contains("..")
                || ziel.getView().startsWith("/") || ziel.getView().contains(":")) {
            throw new IllegalStateException("Deep-Link-Ziel '" + type
                    + "' hat keine gueltige View (erwartet z.B. \"auszahlungen.html\"): " + ziel.getView());
        }
        if (targets.containsKey(type)) {
            throw new IllegalStateException("Deep-Link-Typ '" + type + "' ist doppelt registriert: "
                    + targets.get(type).getClass().getName() + " und " + ziel.getClass().getName());
        }
        targets.put(type, ziel);
    }

    @Override
    public String buildAbsoluteLink(String type, String mandat, String id) {
        return baseUrl + buildRelativeLink(type, mandat, id);
    }

    @Override
    public String buildRelativeLink(String type, String mandat, String id) {
        String t = type == null ? null : type.toLowerCase(Locale.ROOT);
        String m = mandat == null ? null : mandat.toLowerCase(Locale.ROOT);
        if (!DeepLinkFormat.istGueltigerType(t) || !targets.containsKey(t)) {
            throw new IllegalArgumentException("Unbekannter Deep-Link-Typ: " + type);
        }
        if (!DeepLinkFormat.istGueltigesMandat(m)) {
            throw new IllegalArgumentException("Ungueltiges Mandat fuer Deep-Link: " + mandat);
        }
        if (!DeepLinkFormat.istGueltigeId(id)) {
            throw new IllegalArgumentException("Ungueltige Id fuer Deep-Link: " + id);
        }
        return DEEPLINK_PATH + "?type=" + t + "&mandat=" + m + "&id=" + id;
    }

    @Override
    public List<DeepLinkTarget> getTargets() {
        return List.copyOf(targets.values());
    }

    @Override
    public Optional<DeepLinkTarget> findTarget(String type) {
        if (type == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(targets.get(type.toLowerCase(Locale.ROOT)));
    }

    /**
     * Removes trailing slashes without a regular expression.
     *
     * <p>Card 458 (java:S5852): {@code replaceAll("/+$", "")} runs into quadratic backtracking on
     * many consecutive slashes. This loop is linear.</p>
     */
    private static String ohneEndSchraegstriche(String wert) {
        int ende = wert.length();
        while (ende > 0 && wert.charAt(ende - 1) == '/') {
            ende--;
        }
        return wert.substring(0, ende);
    }
}
