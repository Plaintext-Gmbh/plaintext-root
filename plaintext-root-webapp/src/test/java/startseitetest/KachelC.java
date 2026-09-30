/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package startseitetest;

import ch.plaintext.boot.dashboard.DashboardTile;

/**
 * Karte 1351: Test-Kachel fuer {@code StartseiteAnpassenPlaywrightIT}. Liegt bewusst AUSSERHALB
 * von {@code ch.plaintext}: nur der Test setzt {@code plaintext.dashboard.scan-package} auf dieses
 * Paket, alle anderen Tests sehen sie nicht.
 */
@DashboardTile(id = "pw-c", title = "Kachel C", icon = "pi pi-box", link = "index.html",
        menuTitle = "Kachel C", order = 3, roles = {"USER", "ADMIN", "ROOT"})
public class KachelC {
}
