/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

import ch.plaintext.jpa.service.EntityRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for "clear data" of a module (Task #016 phase 2, PR 4). */
@ExtendWith(MockitoExtension.class)
class ModuleDangerZoneServiceTest {

    @Mock
    private ModuleDataService moduleDataService;

    @Mock
    private EntityRegistryService registryService;

    @Mock
    private JpaRepository<Object, Long> widgetRepository;

    private ModuleDangerZoneService service;

    static class Widget {
    }

    static class TestModule implements ModuleDescriptor {
        @Override
        public String moduleId() {
            return "testmodule";
        }

        @Override
        public String displayName() {
            return "Test-Modul";
        }

        @Override
        public List<Class<?>> entities() {
            return List.of(Widget.class);
        }
    }

    @BeforeEach
    void setUp() {
        service = new ModuleDangerZoneService(List.of(new TestModule()), moduleDataService, registryService);
    }

    @Test
    void clearData_unknownModule_throws() {
        assertThatThrownBy(() -> service.clearData("unbekannt", "egal"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(moduleDataService, registryService);
    }

    @Test
    void clearData_falscheBestaetigung_wirftUndLoeschtNichts() {
        assertThatThrownBy(() -> service.clearData("testmodule", "Falscher Name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bestätigung");
        verifyNoInteractions(moduleDataService, registryService);
    }

    @Test
    void clearData_leereBestaetigung_wirftUndLoeschtNichts() {
        assertThatThrownBy(() -> service.clearData("testmodule", null))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(moduleDataService, registryService);
    }

    @Test
    void clearData_exportSchlaegtFehl_brichtVorDemLoeschenAb() {
        when(moduleDataService.export("testmodule")).thenThrow(new IllegalStateException("Export kaputt"));

        assertThatThrownBy(() -> service.clearData("testmodule", "Test-Modul"))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(registryService);
    }

    @Test
    void clearData_erfolgreich_loeschtAlleZeilenUndLiefertBackup() {
        when(moduleDataService.export("testmodule")).thenReturn("{\"module\":\"testmodule\"}");
        doReturn(widgetRepository).when(registryService).getRepository("Widget");
        when(widgetRepository.count()).thenReturn(3L);

        ModuleDangerZoneService.ClearResult result = service.clearData("testmodule", "Test-Modul");

        assertThat(result.exportJson()).isEqualTo("{\"module\":\"testmodule\"}");
        assertThat(result.geloeschtProEntity()).containsEntry("Widget", 3);
        verify(widgetRepository).deleteAll();
    }

    @Test
    void clearData_keinRepositoryGefunden_wirft() {
        when(moduleDataService.export("testmodule")).thenReturn("{}");
        doReturn(mock(Object.class)).when(registryService).getRepository("Widget");

        assertThatThrownBy(() -> service.clearData("testmodule", "Test-Modul"))
                .isInstanceOf(IllegalStateException.class);
    }
}
