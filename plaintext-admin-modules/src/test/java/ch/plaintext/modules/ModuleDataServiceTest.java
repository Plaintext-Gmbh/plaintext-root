/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.modules;

import ch.plaintext.jpa.service.JpaEntityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/** Unit tests for the JSON export/import of a module data set (Task #016 phase 2, PR 3). */
@ExtendWith(MockitoExtension.class)
class ModuleDataServiceTest {

    @Mock
    private JpaEntityService entityService;

    private ModuleDataService service;

    static class Widget {
        public Long id;
        public String name;

        public Widget() {
        }

        public Widget(Long id, String name) {
            this.id = id;
            this.name = name;
        }
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
        service = new ModuleDataService(List.of(new TestModule()), entityService);
    }

    @Test
    void export_unknownModule_throws() {
        assertThatThrownBy(() -> service.export("unbekannt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unbekannt");
    }

    @Test
    void export_buildsEnvelopeWithModuleAndTables() {
        doReturn(List.of(new Widget(1L, "eins"))).when(entityService).findAll("Widget");

        String json = service.export("testmodule");

        assertThat(json).contains("\"module\" : \"testmodule\"");
        assertThat(json).contains("\"Widget\"");
        assertThat(json).contains("\"eins\"");
    }

    @Test
    void importData_unknownModule_throws() {
        assertThatThrownBy(() -> service.importData("unbekannt", "{}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void importData_wrongModuleInFile_throws() {
        byte[] json = "{\"module\":\"anderes\",\"tables\":{}}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.importData("testmodule", json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anderes");
    }

    @Test
    void importData_invalidJson_throws() {
        assertThatThrownBy(() -> service.importData("testmodule", "kein json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exportUndImport_roundtrip_speichertZeilen() {
        doReturn(List.of(new Widget(1L, "eins"), new Widget(2L, "zwei"))).when(entityService).findAll("Widget");
        String json = service.export("testmodule");

        ModuleDataService.ImportResult result = service.importData("testmodule", json.getBytes(StandardCharsets.UTF_8));

        assertThat(result.gesamt()).isEqualTo(2);
        assertThat(result.gespeichert()).isEqualTo(2);
        assertThat(result.fehler()).isEmpty();
        verify(entityService, org.mockito.Mockito.times(2)).save(eq("Widget"), any(Widget.class));
    }

    @Test
    void importData_unbekannteTabelle_wirdUebersprungenMitFehlermeldung() {
        byte[] json = "{\"module\":\"testmodule\",\"tables\":{\"Unbekannt\":[{\"id\":1}]}}"
                .getBytes(StandardCharsets.UTF_8);

        ModuleDataService.ImportResult result = service.importData("testmodule", json);

        assertThat(result.gesamt()).isZero();
        assertThat(result.fehler()).hasSize(1);
        assertThat(result.fehler().get(0)).contains("Unbekannt");
    }

    @Test
    void importData_speicherFehler_brichtImportNichtAb() {
        doReturn(List.of(new Widget(1L, "eins"), new Widget(2L, "zwei"))).when(entityService).findAll("Widget");
        String json = service.export("testmodule");
        doThrow(new RuntimeException("db kaputt")).when(entityService).save(eq("Widget"), any(Widget.class));

        ModuleDataService.ImportResult result = service.importData("testmodule", json.getBytes(StandardCharsets.UTF_8));

        assertThat(result.gesamt()).isEqualTo(2);
        assertThat(result.gespeichert()).isZero();
        assertThat(result.fehler()).hasSize(2);
    }
}
