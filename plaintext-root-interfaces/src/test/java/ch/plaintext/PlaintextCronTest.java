/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext;

import ch.plaintext.bus.ExecutionScope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlaintextCronTest {

    @Test
    void isGlobal_defaultIsFalse() {
        PlaintextCron cron = mandant -> {};
        assertFalse(cron.isGlobal());
    }

    @Test
    void getDisplayName_defaultIsSimpleClassName() {
        PlaintextCron cron = new PlaintextCron() {
            @Override
            public void run(String mandant) {}
        };

        // Anonymous class returns a non-null name derived from class
        assertNotNull(cron.getDisplayName());
    }

    @Test
    void getDefaultCronExpression_defaultIsMidnight() {
        PlaintextCron cron = mandant -> {};
        assertEquals("0 0 * * *", cron.getDefaultCronExpression());
    }

    @Test
    void isGlobal_canBeOverridden() {
        PlaintextCron cron = new PlaintextCron() {
            @Override
            public boolean isGlobal() {
                return true;
            }

            @Override
            public void run(String mandant) {}
        };

        assertTrue(cron.isGlobal());
    }

    @Test
    void getDefaultCronExpression_canBeOverridden() {
        PlaintextCron cron = new PlaintextCron() {
            @Override
            public String getDefaultCronExpression() {
                return "*/5 * * * *";
            }

            @Override
            public void run(String mandant) {}
        };

        assertEquals("*/5 * * * *", cron.getDefaultCronExpression());
    }

    @Test
    void getScope_defaultMandatWennNichtGlobal() {
        PlaintextCron cron = mandant -> {};
        assertEquals(ExecutionScope.MANDAT, cron.getScope());
    }

    @Test
    void getScope_applicationWennGlobal() {
        PlaintextCron cron = new PlaintextCron() {
            @Override
            public boolean isGlobal() {
                return true;
            }

            @Override
            public void run(String mandant) {}
        };

        assertEquals(ExecutionScope.APPLICATION, cron.getScope());
    }
}
