/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import ch.plaintext.PlaintextCron;
import ch.plaintext.PlaintextSecurity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Card 1521: rows of a cron whose class is not loaded (module switched off) are listed as orphans and
 * are never scheduled; rows of loaded crons are not orphans.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CronVerwaisteTest {

    @Mock
    private CronConfigStore cronConfigStore;
    @Spy
    private CronProperties cronProperties = new CronProperties();
    @Mock
    private ApplicationContext ctx;
    @Mock
    private PlaintextSecurity plaintextSecurity;
    @InjectMocks
    private CronController cronController;

    private static CronConfigEntity zeile(String name, String mandat) {
        CronConfigEntity e = new CronConfigEntity();
        e.setCronName(name);
        e.setMandat(mandat);
        e.setCronExpression("* * * * *");
        e.setEnabled(true);
        e.setStartup(true);
        return e;
    }

    @Test
    void zeileOhneGeladeneKlasseIstVerwaistUndWirdNichtGeplant() throws Exception {
        SuperCron geladen = new SuperCron() {
            @Override
            public void run(String mandant) {
                // no-op
            }

            @Override
            public boolean isGlobal() {
                return true;
            }

            @Override
            public String getName() {
                return "GeladenCron";
            }
        };
        Field f = CronController.class.getDeclaredField("crons");
        f.setAccessible(true);
        f.set(cronController, new ArrayList<>(List.<PlaintextCron>of(geladen)));

        CronConfigEntity geladeneZeile = zeile("GeladenCron", "global");
        geladeneZeile.setEnabled(false);
        CronConfigEntity waise = zeile("WikiMailCron", "global");
        when(plaintextSecurity.getAllMandate()).thenReturn(Set.of());
        when(cronConfigStore.findAll()).thenReturn(List.of(geladeneZeile, waise));
        when(cronConfigStore.findByCronNameAndMandat("GeladenCron", "global")).thenReturn(Optional.of(geladeneZeile));
        when(cronConfigStore.save(any(CronConfigEntity.class))).thenAnswer(i -> i.getArgument(0));

        cronController.init();

        assertThat(cronController.getVerwaiste()).containsExactly(waise);
        Field ids = CronController.class.getDeclaredField("cronsId");
        ids.setAccessible(true);
        assertThat(((Map<?, ?>) ids.get(cronController)).keySet()).noneMatch(k -> k.toString().startsWith("WikiMailCron"));
        assertThat(cronController.getCronsMap().values().stream().flatMap(List::stream))
                .extracting(CronConfigEntity::getCronName).doesNotContain("WikiMailCron");
    }

    @Test
    void ohneZeilenKeineWaisen() {
        when(plaintextSecurity.getAllMandate()).thenReturn(Set.of());
        when(cronConfigStore.findAll()).thenReturn(List.of());

        cronController.init();

        assertThat(cronController.getVerwaiste()).isEmpty();
    }

    @Test
    void backingBeanZeigtNichtRootNurDasEigeneMandat() throws Exception {
        CronController controller = org.mockito.Mockito.mock(CronController.class);
        CronConfigEntity a = zeile("X", "mandatA");
        CronConfigEntity b = zeile("X", "mandatB");
        when(controller.getVerwaiste()).thenReturn(List.of(a, b));
        CronBackingBean bean = new CronBackingBean();
        Field c = CronBackingBean.class.getDeclaredField("cronController");
        c.setAccessible(true);
        c.set(bean, controller);
        Field s = CronBackingBean.class.getDeclaredField("plaintextSecurity");
        s.setAccessible(true);
        s.set(bean, plaintextSecurity);

        when(plaintextSecurity.ifGranted("root")).thenReturn(false);
        when(plaintextSecurity.getMandat()).thenReturn("mandatA");
        assertThat(bean.getVerwaiste()).containsExactly(a);
        assertThat(bean.isVerwaisteAusblenden()).isTrue();

        when(plaintextSecurity.ifGranted("root")).thenReturn(true);
        assertThat(bean.getVerwaiste()).containsExactly(a, b);
    }
}
