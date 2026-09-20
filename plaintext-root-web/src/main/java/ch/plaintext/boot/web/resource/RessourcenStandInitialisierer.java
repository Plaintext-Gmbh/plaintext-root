/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Setzt beim Hochfahren einmal die Marke aus {@link RessourcenStand} — Karte 1311.
 *
 * <p>Eigene Klasse und kein {@code String}-Bean: ein Bean vom Typ {@code String} beantwortet
 * jede Einspritzung nach Typ und ist damit eine Falle fuer fremden Code.
 *
 * @author info@plaintext.ch
 * @since 2026
 */
public class RessourcenStandInitialisierer {

    private static final Logger LOG = LoggerFactory.getLogger(RessourcenStandInitialisierer.class);

    /**
     * @param version die Projektversion, roh
     */
    public RessourcenStandInitialisierer(String version) {
        RessourcenStand.setze(version);
        LOG.info("Ressourcenadressen tragen ab jetzt {}={} (Karte 1311, Projektversion '{}')",
                StandResourceHandler.PARAMETER, RessourcenStand.stand(), version);
    }

    /** @return die gesetzte Marke. */
    public String stand() {
        return RessourcenStand.stand();
    }
}
