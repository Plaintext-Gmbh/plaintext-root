/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import ch.plaintext.bus.ExecutionScope;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CronConfigEntityTest {

    @Test
    void getScopeReturnsMandatWhenCronIsNull() {
        CronConfigEntity entity = new CronConfigEntity();
        entity.setCron(null);

        assertThat(entity.getScope()).isEqualTo(ExecutionScope.MANDAT);
    }

    @Test
    void getScopeDelegatesToCron() {
        CronConfigEntity entity = new CronConfigEntity();
        SuperCron cron = mock(SuperCron.class);
        when(cron.getScope()).thenReturn(ExecutionScope.PERSOENLICH);
        entity.setCron(cron);

        assertThat(entity.getScope()).isEqualTo(ExecutionScope.PERSOENLICH);
    }

    @Test
    void defaultValuesAreSetCorrectly() {
        CronConfigEntity entity = new CronConfigEntity();

        assertThat(entity.isEnabled()).isTrue();
        assertThat(entity.isStartup()).isFalse();
        assertThat(entity.getCounter()).isZero();
        assertThat(entity.getLastSeconds()).isZero();
    }

    @Test
    void isGlobalReturnsFalseWhenCronIsNull() {
        CronConfigEntity entity = new CronConfigEntity();
        entity.setCron(null);

        assertThat(entity.isGlobal()).isFalse();
    }

    @Test
    void isGlobalDelegatesToCron() {
        CronConfigEntity entity = new CronConfigEntity();
        SuperCron cron = mock(SuperCron.class);
        when(cron.isGlobal()).thenReturn(true);
        entity.setCron(cron);

        assertThat(entity.isGlobal()).isTrue();
    }

    @Test
    void getPercenteReturnsZeroWhenCronIsNull() {
        CronConfigEntity entity = new CronConfigEntity();
        entity.setCron(null);

        assertThat(entity.getPercente()).isZero();
    }

    @Test
    void getPercenteDelegatesToCron() {
        CronConfigEntity entity = new CronConfigEntity();
        SuperCron cron = mock(SuperCron.class);
        when(cron.getPercente()).thenReturn(50);
        entity.setCron(cron);

        assertThat(entity.getPercente()).isEqualTo(50);
    }

    @Test
    void getDisplayNameReturnsCronNameWhenCronIsNull() {
        CronConfigEntity entity = new CronConfigEntity();
        entity.setCronName("MyCron");
        entity.setCron(null);

        assertThat(entity.getDisplayName()).isEqualTo("MyCron");
    }

    @Test
    void getDisplayNameDelegatesToCron() {
        CronConfigEntity entity = new CronConfigEntity();
        SuperCron cron = mock(SuperCron.class);
        when(cron.getDisplayName()).thenReturn("Pretty Name");
        entity.setCron(cron);

        assertThat(entity.getDisplayName()).isEqualTo("Pretty Name");
    }

    @Test
    void syncFromCronCopiesValuesFromCron() {
        CronConfigEntity entity = new CronConfigEntity();
        SuperCron cron = mock(SuperCron.class);
        Date now = new Date();

        when(cron.getCounter()).thenReturn(5);
        when(cron.getLastRun()).thenReturn(now);
        when(cron.getLastSeconds()).thenReturn(30);

        entity.setCron(cron);
        entity.syncFromCron();

        assertThat(entity.getCounter()).isEqualTo(5);
        assertThat(entity.getLastRun()).isEqualTo(now);
        assertThat(entity.getLastSeconds()).isEqualTo(30);
    }

    @Test
    void syncFromCronDoesNothingWhenCronIsNull() {
        CronConfigEntity entity = new CronConfigEntity();
        entity.setCounter(10);
        entity.setCron(null);

        entity.syncFromCron();

        assertThat(entity.getCounter()).isEqualTo(10);
    }

    @Test
    void settersAndGettersWorkCorrectly() {
        CronConfigEntity entity = new CronConfigEntity();
        Date now = new Date();

        entity.setId(1L);
        entity.setCronName("TestCron");
        entity.setMandat("mandatA");
        entity.setCronExpression("0 6 * * *");
        entity.setEnabled(false);
        entity.setStartup(true);
        entity.setCounter(3);
        entity.setLastRun(now);
        entity.setLastSeconds(15);

        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getCronName()).isEqualTo("TestCron");
        assertThat(entity.getMandat()).isEqualTo("mandatA");
        assertThat(entity.getCronExpression()).isEqualTo("0 6 * * *");
        assertThat(entity.isEnabled()).isFalse();
        assertThat(entity.isStartup()).isTrue();
        assertThat(entity.getCounter()).isEqualTo(3);
        assertThat(entity.getLastRun()).isEqualTo(now);
        assertThat(entity.getLastSeconds()).isEqualTo(15);
    }

    @Test
    void auditFieldsWorkCorrectly() {
        CronConfigEntity entity = new CronConfigEntity();
        Date now = new Date();

        entity.setCreatedBy("admin");
        entity.setCreatedDate(now);
        entity.setLastModifiedBy("editor");
        entity.setLastModifiedDate(now);

        assertThat(entity.getCreatedBy()).isEqualTo("admin");
        assertThat(entity.getCreatedDate()).isEqualTo(now);
        assertThat(entity.getLastModifiedBy()).isEqualTo("editor");
        assertThat(entity.getLastModifiedDate()).isEqualTo(now);
    }
}
