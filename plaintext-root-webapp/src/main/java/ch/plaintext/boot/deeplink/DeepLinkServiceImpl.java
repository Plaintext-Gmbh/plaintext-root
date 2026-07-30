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
 * Registry + Link-Bau fuer Deep-Links (Karte 345).
 *
 * <p>Die Registry entsteht rein aus den im Kontext vorhandenen {@link DeepLinkTarget}-Beans; ein
 * Modul „meldet sich an", indem es eine solche Bean bereitstellt. Es wird nichts persistiert —
 * Deep-Links sind reine Adressen, keine Tokens. Genau deshalb verleihen sie auch keine
 * Berechtigung: es gibt kein Geheimnis, das man vorzeigen koennte, jeder Aufruf wird beim
 * Oeffnen frisch gegen die Rechte des angemeldeten Benutzers geprueft.
 */
@Service
@Slf4j
public class DeepLinkServiceImpl implements DeepLinkService {

    private final Map<String, DeepLinkTarget> targets = new LinkedHashMap<>();
    private final String baseUrl;

    public DeepLinkServiceImpl(List<DeepLinkTarget> registrierteZiele,
                               @Value("${plaintext.baseurl:http://localhost:8080}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        List<DeepLinkTarget> sortiert = new ArrayList<>(registrierteZiele);
        sortiert.sort(Comparator.comparing(t -> String.valueOf(t.getType())));
        for (DeepLinkTarget ziel : sortiert) {
            registriere(ziel);
        }
        log.info("Deep-Link-Registry: {} Ziel(e) registriert: {}", targets.size(), targets.keySet());
    }

    /**
     * Fail-fast beim Start statt einer stillen Luecke zur Laufzeit: ein Ziel mit unsauberem
     * {@code type} oder ohne View wuerde beim Aufruf ohnehin abgelehnt — dann lieber sofort
     * sichtbar. Doppelte Typen werden abgelehnt, weil sonst unklar waere, welches Modul (und damit
     * welche Zugriffspruefung) fuer einen Link zustaendig ist.
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
}
