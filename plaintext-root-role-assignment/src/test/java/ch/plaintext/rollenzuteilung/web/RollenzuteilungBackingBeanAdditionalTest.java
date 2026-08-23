/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.rollenzuteilung.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.rollenzuteilung.entity.Rollenzuteilung;
import ch.plaintext.rollenzuteilung.service.RollenzuteilungService;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Additional tests for RollenzuteilungBackingBean covering FacesContext-dependent methods.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RollenzuteilungBackingBean Additional Tests")
class RollenzuteilungBackingBeanAdditionalTest {

    @Mock
    private RollenzuteilungService service;

    @Mock
    private PlaintextSecurity security;

    @Mock
    private FacesContext facesContext;

    @Mock
    private ExternalContext externalContext;

    private RollenzuteilungBackingBean bean;
    private MockedStatic<FacesContext> facesContextMock;

    @BeforeEach
    void setUp() {
        bean = new RollenzuteilungBackingBean(service, security);
        facesContextMock = mockStatic(FacesContext.class);
        facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
        lenient().when(facesContext.getExternalContext()).thenReturn(externalContext);
    }

    @AfterEach
    void tearDown() {
        facesContextMock.close();
    }

    @Nested
    @DisplayName("onLoad")
    class OnLoad {

        @Test
        @DisplayName("Should set admin true when user has ROLE_ADMIN")
        void shouldSetAdminTrueWhenUserHasRoleAdmin() {
            when(security.ifGranted("ROLE_ADMIN")).thenReturn(true);
            when(service.getAllRollenzuteilungenForCurrentUser()).thenReturn(List.of());

            bean.onLoad();

            assertTrue(bean.isAdmin());
        }

        @Test
        @DisplayName("Should set admin true when user has ROLE_ROOT")
        void shouldSetAdminTrueWhenUserHasRoleRoot() {
            when(security.ifGranted("ROLE_ADMIN")).thenReturn(false);
            when(security.ifGranted("ROLE_ROOT")).thenReturn(true);
            when(service.getAllRollenzuteilungenForCurrentUser()).thenReturn(List.of());

            bean.onLoad();

            assertTrue(bean.isAdmin());
        }

        @Test
        @DisplayName("Should set admin false when user has neither admin nor root role")
        void shouldSetAdminFalseWhenNoAdminRole() {
            when(security.ifGranted("ROLE_ADMIN")).thenReturn(false);
            when(security.ifGranted("ROLE_ROOT")).thenReturn(false);

            bean.onLoad();

            assertFalse(bean.isAdmin());
        }

        @Test
        @DisplayName("Should load data on GET when authorized")
        void shouldLoadDataDuringInit() {
            when(security.ifGranted("ROLE_ADMIN")).thenReturn(true);
            List<Rollenzuteilung> expected = List.of(new Rollenzuteilung());
            when(service.getAllRollenzuteilungenForCurrentUser()).thenReturn(expected);

            bean.onLoad();

            assertEquals(expected, bean.getRollenzuteilungen());
        }

        @Test
        @DisplayName("Should handle data loading exception")
        void shouldHandleDataLoadingException() {
            when(security.ifGranted("ROLE_ADMIN")).thenReturn(true);
            when(service.getAllRollenzuteilungenForCurrentUser())
                    .thenThrow(new RuntimeException("DB Error"));

            bean.onLoad();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }

        @Test
        @DisplayName("Should redirect and NOT load data when user is not admin")
        void shouldRedirectWhenNotAdmin() throws IOException {
            when(security.ifGranted("ROLE_ADMIN")).thenReturn(false);
            when(security.ifGranted("ROLE_ROOT")).thenReturn(false);

            bean.onLoad();

            verify(externalContext).redirect("/index.xhtml");
            verify(service, never()).getAllRollenzuteilungenForCurrentUser();
        }

        @Test
        @DisplayName("Should not redirect when user is admin")
        void shouldNotRedirectWhenAdmin() throws IOException {
            when(security.ifGranted("ROLE_ADMIN")).thenReturn(true);
            when(service.getAllRollenzuteilungenForCurrentUser()).thenReturn(List.of());

            bean.onLoad();

            verify(externalContext, never()).redirect(anyString());
        }

        @Test
        @DisplayName("Should handle redirect failure gracefully")
        void shouldHandleRedirectFailure() throws IOException {
            when(security.ifGranted("ROLE_ADMIN")).thenReturn(false);
            when(security.ifGranted("ROLE_ROOT")).thenReturn(false);

            doThrow(new IOException("Redirect error")).when(externalContext).redirect(anyString());

            assertDoesNotThrow(() -> bean.onLoad());
        }
    }

    @Nested
    @DisplayName("save")
    class SaveTests {

        @Test
        @DisplayName("Should warn when selected is null")
        void shouldWarnWhenSelectedIsNull() {
            bean.setSelected(null);

            bean.save();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_WARN));
        }

        @Test
        @DisplayName("Should warn when username is null")
        void shouldWarnWhenUsernameIsNull() {
            Rollenzuteilung rz = new Rollenzuteilung();
            rz.setUsername(null);
            rz.setRoleName("ROLE_USER");
            bean.setSelected(rz);

            bean.save();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_WARN));
        }

        @Test
        @DisplayName("Should warn when username is empty")
        void shouldWarnWhenUsernameIsEmpty() {
            Rollenzuteilung rz = new Rollenzuteilung();
            rz.setUsername("   ");
            rz.setRoleName("ROLE_USER");
            bean.setSelected(rz);

            bean.save();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_WARN));
        }

        @Test
        @DisplayName("Should warn when roleName is null")
        void shouldWarnWhenRoleNameIsNull() {
            Rollenzuteilung rz = new Rollenzuteilung();
            rz.setUsername("user1");
            rz.setRoleName(null);
            bean.setSelected(rz);

            bean.save();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_WARN));
        }

        @Test
        @DisplayName("Should warn when roleName is empty")
        void shouldWarnWhenRoleNameIsEmpty() {
            Rollenzuteilung rz = new Rollenzuteilung();
            rz.setUsername("user1");
            rz.setRoleName("  ");
            bean.setSelected(rz);

            bean.save();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_WARN));
        }

        @Test
        @DisplayName("Should save successfully and reload data")
        void shouldSaveSuccessfullyAndReloadData() {
            Rollenzuteilung rz = new Rollenzuteilung();
            rz.setUsername("user1");
            rz.setRoleName("ROLE_WIKI");
            bean.setSelected(rz);
            when(service.save(rz)).thenReturn(rz);
            when(service.getAllRollenzuteilungenForCurrentUser()).thenReturn(List.of(rz));

            bean.save();

            verify(service).save(rz);
            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_INFO));
        }

        @Test
        @DisplayName("Should show error when save throws exception")
        void shouldShowErrorWhenSaveThrows() {
            Rollenzuteilung rz = new Rollenzuteilung();
            rz.setUsername("user1");
            rz.setRoleName("ROLE_WIKI");
            bean.setSelected(rz);
            when(service.save(rz)).thenThrow(new RuntimeException("DB Error"));

            bean.save();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Nested
    @DisplayName("delete")
    class DeleteTests {

        @Test
        @DisplayName("Should warn when selected is null")
        void shouldWarnWhenSelectedIsNull() {
            bean.setSelected(null);

            bean.delete();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_WARN));
        }

        @Test
        @DisplayName("Should delete successfully and clear selection")
        void shouldDeleteSuccessfullyAndClearSelection() {
            Rollenzuteilung rz = new Rollenzuteilung();
            rz.setId(42L);
            bean.setSelected(rz);
            when(service.getAllRollenzuteilungenForCurrentUser()).thenReturn(List.of());

            bean.delete();

            verify(service).delete(42L);
            assertNull(bean.getSelected());
            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_INFO));
        }

        @Test
        @DisplayName("Should show error when delete throws exception")
        void shouldShowErrorWhenDeleteThrows() {
            Rollenzuteilung rz = new Rollenzuteilung();
            rz.setId(1L);
            bean.setSelected(rz);
            doThrow(new RuntimeException("DB Error")).when(service).delete(1L);

            bean.delete();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Nested
    @DisplayName("newRollenzuteilung")
    class NewRollenzuteilung {

        @Test
        @DisplayName("Should set mandat from security")
        void shouldSetMandatFromSecurity() {
            when(security.getMandat()).thenReturn("my-mandat");

            bean.newRollenzuteilung();

            assertNotNull(bean.getSelected());
            assertEquals("my-mandat", bean.getSelected().getMandat());
            assertTrue(bean.getSelected().getActive());
        }
    }

    @Nested
    @DisplayName("getAvailableRoles (Registry + Bestand)")
    class GetAvailableRoles {

        @Test
        @DisplayName("Without registry, roles come from the Bestand (DB)")
        void withoutRegistryUsesBestand() {
            when(service.getDistinctRoleNames()).thenReturn(List.of("ROLE_PRIVATAUSGABEN", "ROLE_ADMIN"));

            List<String> roles = bean.getAvailableRoles();

            assertEquals(List.of("ROLE_ADMIN", "ROLE_PRIVATAUSGABEN"), roles);
        }

        @Test
        @DisplayName("Bestand role that is no longer declared stays selectable")
        void undeclaredBestandRoleStaysSelectable() {
            when(service.getDistinctRoleNames()).thenReturn(List.of("ROLE_PRIVATAUSGABEN"));

            assertTrue(bean.getAvailableRoles().contains("ROLE_PRIVATAUSGABEN"));
        }

        @Test
        @DisplayName("Empty when neither registry nor Bestand provide roles")
        void emptyWithoutSources() {
            when(service.getDistinctRoleNames()).thenReturn(List.of());

            assertTrue(bean.getAvailableRoles().isEmpty());
        }
    }
}
