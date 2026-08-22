/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verdrahtung im echten Spring-Kontext: {@code plaintext.menu.module-roles} muss binden, den
 * {@link ModuleRoleService} als Bean liefern und die im Klassenpfad gefundenen Menuepunkte
 * ({@code MenuRegistryPostProcessor}) auffuellen.
 */
class ModuleRoleWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MenuAutoConfiguration.class))
            .withPropertyValues("plaintext.menu.scan-package=ch.plaintext.boot.menu");

    @Test
    void ohneKonfigurationIstDerServiceDaUndFordertNichts() {
        runner.run(context -> {
            assertTrue(context.containsBean("moduleRoleService"));
            ModuleRoleService service = context.getBean(ModuleRoleService.class);
            assertTrue(context.getBean(ModuleRoleProperties.class).isEmpty());
            assertTrue(service.getKnownModuleKeys().contains("testmodule"),
                    "erkannte Keys: " + service.getKnownModuleKeys());
            for (MenuItemImpl item : context.getBeansOfType(MenuItemImpl.class).values()) {
                assertTrue(item.getModuleRoles().isEmpty());
            }
        });
    }

    @Test
    void konfigurationBindetUndWirdAufDieMenuepunkteGeschrieben() {
        runner.withPropertyValues("plaintext.menu.module-roles.testmodule=wiki").run(context -> {
            ModuleRoleProperties properties = context.getBean(ModuleRoleProperties.class);
            assertEquals(Map.of("testmodule", "WIKI"), properties.canonicalModuleRoles());

            Map<String, MenuItemImpl> menues = context.getBeansOfType(MenuItemImpl.class);
            assertFalse(menues.isEmpty(), "MenuRegistryPostProcessor muss Menuepunkte finden");
            boolean getroffen = menues.values().stream()
                    .anyMatch(m -> m.getModuleRoles().contains("WIKI"));
            assertTrue(getroffen, "Menuepunkt des Moduls 'testmodule' muss die Rolle WIKI fordern");
        });
    }

    @Test
    void unbekannterKeyLaesstDenStartUnberuehrt() {
        runner.withPropertyValues("plaintext.menu.module-roles.gibtesnicht=irgendwas").run(context -> {
            assertFalse(context.getStartupFailure() != null, "unbekannter Key darf den Start nicht brechen");
            ModuleRoleService service = context.getBean(ModuleRoleService.class);
            assertFalse(service.getKnownModuleKeys().contains("gibtesnicht"));
        });
    }
}
