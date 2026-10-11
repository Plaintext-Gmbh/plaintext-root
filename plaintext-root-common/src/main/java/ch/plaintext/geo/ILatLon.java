/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.geo;

import ch.plaintext.arch.StabileApi;

/**
 * The minimum {@link AbstractHoehenService} needs from a track point: WGS84 coordinates.
 *
 * <p>Deliberately so narrow that the track point records of the business modules satisfy it without
 * any adjustment: a {@code record TrackPoint(double lat, double lon, ...)} implements the
 * interface through its accessor methods alone. This keeps the elevation service module-neutral
 * without the modules having to share a common point type.</p>
 *
 * @author info@plaintext.ch
 * @since 2026
 */
@StabileApi("Koordinate fuer Hoehendienste (Karte 1504, aus app-interfaces)")
public interface ILatLon {

    /** Latitude (WGS84, decimal degrees). */
    double lat();

    /** Longitude (WGS84, decimal degrees). */
    double lon();
}
