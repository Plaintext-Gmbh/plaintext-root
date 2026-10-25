/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.menu.MenuItemImpl;
import ch.plaintext.rollenzuteilung.entity.Rollenzuteilung;
import ch.plaintext.rollenzuteilung.service.RollenzuteilungService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Card 1521: roles of a module that is not loaded are recognised exactly, unknown origin never. */
class VerwaisteRollenTest {

    private static MenuItemImpl menue(String menuId) {
        MenuItemImpl m = new MenuItemImpl();
        m.setMenuId(menuId);
        return m;
    }

    private static final Map<String, String> KONFIG = Map.of("wiki", "WIKI", "fotos", "FOTOS");

    @Test
    void menuRolleOhneGeladenesMenueIstVerwaist() {
        assertThat(VerwaisteRollen.grund("ROLE_MENU_WIKI", List.of(menue("cron")), Set.of(), KONFIG, Set.of()))
                .isEqualTo("wiki");
    }

    @Test
    void menuRolleMitGeladenemMenueIstNichtVerwaist() {
        assertThat(VerwaisteRollen.grund("ROLE_MENU_WIKI", List.of(menue("wiki")), Set.of(), KONFIG, Set.of()))
                .isNull();
    }

    @Test
    void modulRolleOhneGeladenesModulIstVerwaist() {
        assertThat(VerwaisteRollen.grund("ROLE_WIKI", List.of(), Set.of("fotos"), KONFIG, Set.of()))
                .isEqualTo("wiki");
        assertThat(VerwaisteRollen.grund("wiki", List.of(), Set.of("fotos"), KONFIG, Set.of()))
                .isEqualTo("wiki");
    }

    @Test
    void modulRolleMitGeladenemModulIstNichtVerwaist() {
        assertThat(VerwaisteRollen.grund("ROLE_WIKI", List.of(), Set.of("wiki"), KONFIG, Set.of())).isNull();
    }

    @Test
    void modulRolleDieEinAndererAnbieterDeklariertIstNichtVerwaist() {
        assertThat(VerwaisteRollen.grund("ROLE_WIKI", List.of(), Set.of(), KONFIG, Set.of("WIKI"))).isNull();
    }

    @Test
    void modulRolleDieEinGeladenesMenueVerlangtIstNichtVerwaist() {
        MenuItemImpl m = menue("x");
        m.setRoles(List.of("WIKI"));
        assertThat(VerwaisteRollen.grund("ROLE_WIKI", List.of(m), Set.of(), KONFIG, Set.of())).isNull();
        MenuItemImpl p = menue("y");
        p.setRoleStartsWith(List.of("ROLE_WI"));
        assertThat(VerwaisteRollen.grund("ROLE_WIKI", List.of(p), Set.of(), KONFIG, Set.of())).isNull();
    }

    @Test
    void rolleUnbekannterHerkunftIstNieVerwaist() {
        assertThat(VerwaisteRollen.grund("ROLE_POSTKONTO", List.of(), Set.of(), KONFIG, Set.of())).isNull();
        assertThat(VerwaisteRollen.grund("ROLE_ADMIN", List.of(), Set.of(), KONFIG, Set.of())).isNull();
    }

    @Test
    void ohneModulRollenDienstKeineAussageUeberModulRollen() {
        assertThat(VerwaisteRollen.grund("ROLE_WIKI", List.of(), null, KONFIG, Set.of())).isNull();
    }

    @Test
    void backingBeanBlendetVerwaisteStandardmaessigAus() {
        RollenzuteilungService service = mock(RollenzuteilungService.class);
        VerwaisteRollen verwaiste = mock(VerwaisteRollen.class);
        Rollenzuteilung wiki = zuteilung(1L, "ROLE_WIKI");
        Rollenzuteilung admin = zuteilung(2L, "ROLE_ADMIN");
        when(service.getAllRollenzuteilungenForCurrentUser()).thenReturn(List.of(wiki, admin));
        when(verwaiste.gruende(anyCollection())).thenReturn(Map.of("ROLE_WIKI", "wiki"));
        PlaintextSecurity security = mock(PlaintextSecurity.class);
        when(security.ifGranted("ROLE_ROOT")).thenReturn(true);

        RollenzuteilungBackingBean bean = new RollenzuteilungBackingBean();
        bean.setService(service);
        bean.setSecurity(security);
        bean.setVerwaisteRollen(verwaiste);
        bean.onLoad();

        assertThat(bean.getAngezeigteRollenzuteilungen()).containsExactly(admin);
        assertThat(bean.getAnzahlVerwaist()).isEqualTo(1);
        assertThat(bean.verwaistGrund(wiki)).isEqualTo("wiki");
        assertThat(bean.verwaistGrund(admin)).isNull();

        bean.setVerwaisteAusblenden(false);
        assertThat(bean.getAngezeigteRollenzuteilungen()).containsExactly(wiki, admin);
    }

    private static Rollenzuteilung zuteilung(Long id, String rolle) {
        Rollenzuteilung r = new Rollenzuteilung();
        r.setId(id);
        r.setRoleName(rolle);
        return r;
    }
}
