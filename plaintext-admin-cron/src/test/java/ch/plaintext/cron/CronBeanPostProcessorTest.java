/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.cron;

import ch.plaintext.PlaintextCron;
import ch.plaintext.bus.ExecutionScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class CronBeanPostProcessorTest {

    @Mock
    private ApplicationContext applicationContext;

    private CronBeanPostProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new CronBeanPostProcessor();
        processor.setApplicationContext(applicationContext);
    }

    @Test
    void wrapsPlaintextCronIntoSuperCron() {
        PlaintextCron cronBean = new PlaintextCron() {
            @Override
            public void run(String mandant) {
                // test implementation
            }
        };

        Object result = processor.postProcessAfterInitialization(cronBean, "testCron");

        assertThat(result).isInstanceOf(SuperCron.class);
    }

    @Test
    void wrappedSuperCronDelegatesRun() {
        final boolean[] executed = {false};
        PlaintextCron cronBean = new PlaintextCron() {
            @Override
            public void run(String mandant) {
                executed[0] = true;
            }
        };

        SuperCron wrapper = (SuperCron) processor.postProcessAfterInitialization(cronBean, "testCron");
        wrapper.run("testMandat");

        assertThat(executed[0]).isTrue();
    }

    @Test
    void wrappedSuperCronDelegatesIsGlobal() {
        PlaintextCron globalCron = new PlaintextCron() {
            @Override
            public boolean isGlobal() {
                return true;
            }

            @Override
            public void run(String mandant) {
            }
        };

        SuperCron wrapper = (SuperCron) processor.postProcessAfterInitialization(globalCron, "globalCron");
        assertThat(wrapper.isGlobal()).isTrue();
    }

    @Test
    void wrappedSuperCronDelegatesGetScope() {
        // Regression test (Task 005): the wrapper originally delegated ONLY isGlobal(), NOT
        // getScope() -- a getScope() override of the original cron was lost that way
        // (SuperCron.getScope() fell back to the PlaintextCron default, which in turn queries
        // ONLY isGlobal() OF THIS WRAPPER, not the original). Only became visible on the local
        // app boot (DemoPersonalCron ran with scope=MANDAT instead of PERSOENLICH).
        PlaintextCron persoenlichCron = new PlaintextCron() {
            @Override
            public ExecutionScope getScope() {
                return ExecutionScope.PERSOENLICH;
            }

            @Override
            public void run(String mandant) {
            }
        };

        SuperCron wrapper = (SuperCron) processor.postProcessAfterInitialization(persoenlichCron, "persoenlichCron");

        assertThat(wrapper.getScope()).isEqualTo(ExecutionScope.PERSOENLICH);
    }

    @Test
    void wrappedSuperCronDelegatesRunMitUserId() {
        final String[] aufgerufenMit = new String[2];
        PlaintextCron cron = new PlaintextCron() {
            @Override
            public void run(String mandant) {
                throw new AssertionError("sollte run(mandant, userId) nutzen");
            }

            @Override
            public void run(String mandant, String userId) {
                aufgerufenMit[0] = mandant;
                aufgerufenMit[1] = userId;
            }
        };

        SuperCron wrapper = (SuperCron) processor.postProcessAfterInitialization(cron, "userCron");
        // package-private access through the PlaintextCron interface, the way SuperCron.runProPersoenlich() does it
        ((PlaintextCron) wrapper).run("mandatA", "alice");

        assertThat(aufgerufenMit).containsExactly("mandatA", "alice");
    }

    @Test
    void wrappedSuperCronDelegatesDisplayName() {
        PlaintextCron cron = new PlaintextCron() {
            @Override
            public String getDisplayName() {
                return "My Custom Name";
            }

            @Override
            public void run(String mandant) {
            }
        };

        SuperCron wrapper = (SuperCron) processor.postProcessAfterInitialization(cron, "namedCron");
        assertThat(wrapper.getDisplayName()).isEqualTo("My Custom Name");
    }

    @Test
    void wrappedSuperCronDelegatesDefaultCronExpression() {
        PlaintextCron cron = new PlaintextCron() {
            @Override
            public String getDefaultCronExpression() {
                return "30 6 * * *";
            }

            @Override
            public void run(String mandant) {
            }
        };

        SuperCron wrapper = (SuperCron) processor.postProcessAfterInitialization(cron, "customCron");
        assertThat(wrapper.getDefaultCronExpression()).isEqualTo("30 6 * * *");
    }

    @Test
    void doesNotWrapSuperCronInstances() {
        SuperCron alreadySuperCron = new SuperCron() {
            @Override
            public void run(String mandant) {
            }
        };

        Object result = processor.postProcessAfterInitialization(alreadySuperCron, "superCron");
        assertThat(result).isSameAs(alreadySuperCron);
    }

    @Test
    void doesNotWrapNonCronBeans() {
        Object regularBean = new Object();

        Object result = processor.postProcessAfterInitialization(regularBean, "regularBean");
        assertThat(result).isSameAs(regularBean);
    }

    @Test
    void wrapperHasCorrectBeanName() {
        PlaintextCron cron = mandant -> {};

        SuperCron wrapper = (SuperCron) processor.postProcessAfterInitialization(cron, "myCronBean");
        assertThat(wrapper.getBeanName()).isEqualTo("myCronBean");
    }

    @Test
    void wrapperStoresOriginalBeanClass() {
        PlaintextCron cron = new PlaintextCron() {
            @Override
            public void run(String mandant) {
            }
        };

        SuperCron wrapper = (SuperCron) processor.postProcessAfterInitialization(cron, "myCron");
        assertThat(wrapper.getOriginalBeanClass()).isEqualTo(cron.getClass());
    }

    @Test
    void wrapperGetNameReturnsOriginalClassSimpleName() {
        PlaintextCron cron = new PlaintextCron() {
            @Override
            public void run(String mandant) {
            }
        };

        SuperCron wrapper = (SuperCron) processor.postProcessAfterInitialization(cron, "nameCron");
        // getName() delegates to originalClass.getSimpleName()
        // For anonymous classes this is empty, but the method is still exercised
        String name = wrapper.getName();
        assertThat(name).isNotNull();
    }
}
