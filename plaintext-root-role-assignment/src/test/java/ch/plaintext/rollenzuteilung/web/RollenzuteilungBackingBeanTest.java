/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.rollenzuteilung.entity.Rollenzuteilung;
import ch.plaintext.rollenzuteilung.service.RollenzuteilungService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RollenzuteilungBackingBeanTest {

    @Mock
    private RollenzuteilungService service;

    @Mock
    private PlaintextSecurity security;

    private RollenzuteilungBackingBean bean;

    @BeforeEach
    void setUp() {
        bean = new RollenzuteilungBackingBean(service, security);
    }

    @Test
    void getAvailableRoles_combinesRegistryAndBestand() {
        ch.plaintext.framework.PlaintextRoleRegistry registry = mock(ch.plaintext.framework.PlaintextRoleRegistry.class);
        when(registry.getDeclaredAuthorityNames()).thenReturn(
                new java.util.LinkedHashSet<>(List.of("ROLE_ADMIN", "ROLE_ROOT", "ROLE_USER")));
        when(service.getDistinctRoleNames()).thenReturn(List.of("ROLE_POSTKONTO", "ROLE_ADMIN"));

        RollenzuteilungBackingBean beanWithRegistry = new RollenzuteilungBackingBean(service, security, registry);
        List<String> roles = beanWithRegistry.getAvailableRoles();

        assertNotNull(roles);
        // Union of the declared roles and the existing data in the DB, deduplicated and sorted
        assertEquals(List.of("ROLE_ADMIN", "ROLE_POSTKONTO", "ROLE_ROOT", "ROLE_USER"), roles);
    }

    @Test
    void newRollenzuteilung_createsNewEntry() {
        when(security.getMandat()).thenReturn("test-mandat");

        bean.newRollenzuteilung();

        Rollenzuteilung selected = bean.getSelected();
        assertNotNull(selected);
        assertEquals("test-mandat", selected.getMandat());
        assertTrue(selected.getActive());
    }

    @Test
    void clearSelection_setsSelectedToNull() {
        bean.setSelected(new Rollenzuteilung());
        bean.clearSelection();
        assertNull(bean.getSelected());
    }

    @Test
    void select_doesNotThrow() {
        bean.setSelected(new Rollenzuteilung());
        assertDoesNotThrow(() -> bean.select());
    }

    @Test
    void constructorSetsFields() {
        assertNotNull(bean);
    }
}
