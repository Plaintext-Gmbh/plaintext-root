/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.menuesteuerung.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.service.MandateMenuVisibilityService;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Additional tests for MandateMenuBackingBean focusing on FacesContext-dependent methods.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MandateMenuBackingBean Additional Tests")
class MandateMenuBackingBeanAdditionalTest {

    @Mock
    private MandateMenuVisibilityService service;

    @Mock
    private PlaintextSecurity plaintextSecurity;

    @Mock
    private FacesContext facesContext;

    @Mock
    private ExternalContext externalContext;

    @InjectMocks
    private MandateMenuBackingBean backingBean;

    private MockedStatic<FacesContext> facesContextMock;

    @BeforeEach
    void setUp() {
        facesContextMock = mockStatic(FacesContext.class);
        facesContextMock.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
        lenient().when(facesContext.getExternalContext()).thenReturn(externalContext);
    }

    @AfterEach
    void tearDown() {
        facesContextMock.close();
    }

    @Nested
    @DisplayName("save")
    class Save {

        @Test
        @DisplayName("Should show error when selected is null")
        void shouldShowErrorWhenSelectedIsNull() {
            backingBean.setSelected(null);

            backingBean.save();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }

        @Test
        @DisplayName("Should show error when mandate name is null")
        void shouldShowErrorWhenMandateNameIsNull() {
            MandateMenuConfig config = new MandateMenuConfig();
            config.setMandateName(null);
            backingBean.setSelected(config);

            backingBean.save();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }

        @Test
        @DisplayName("Should show error when mandate name is empty")
        void shouldShowErrorWhenMandateNameIsEmpty() {
            MandateMenuConfig config = new MandateMenuConfig();
            config.setMandateName("   ");
            backingBean.setSelected(config);

            backingBean.save();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }

        @Test
        @DisplayName("Should save successfully and redirect")
        void shouldSaveSuccessfullyAndRedirect() throws IOException {
            MandateMenuConfig config = new MandateMenuConfig();
            config.setMandateName("test-mandate");
            config.setHiddenMenus(new HashSet<>(Set.of("Menu1")));
            config.setWhitelistMode(false);
            backingBean.setSelected(config);

            backingBean.save();

            verify(service).saveConfig("test-mandate", config.getHiddenMenus(), false);
            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_INFO));
            verify(externalContext).redirect("mandatemenu.xhtml");
        }

        @Test
        @DisplayName("Should show error when save throws exception")
        void shouldShowErrorWhenSaveThrows() {
            MandateMenuConfig config = new MandateMenuConfig();
            config.setMandateName("test-mandate");
            config.setHiddenMenus(new HashSet<>());
            backingBean.setSelected(config);

            doThrow(new RuntimeException("DB Error"))
                    .when(service).saveConfig(anyString(), anySet(), anyBoolean());

            backingBean.save();

            // Should add error message (the second addMessage call)
            verify(facesContext, atLeastOnce()).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }

        @Test
        @DisplayName("Should save with whitelist mode when enabled")
        void shouldSaveWithWhitelistMode() throws IOException {
            MandateMenuConfig config = new MandateMenuConfig();
            config.setMandateName("wl-mandate");
            config.setHiddenMenus(new HashSet<>(Set.of("A", "B")));
            config.setWhitelistMode(true);
            backingBean.setSelected(config);

            backingBean.save();

            verify(service).saveConfig("wl-mandate", config.getHiddenMenus(), true);
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("Should show error when selected is null")
        void shouldShowErrorWhenSelectedIsNull() {
            backingBean.setSelected(null);

            backingBean.delete();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }

        @Test
        @DisplayName("Should delete successfully and redirect")
        void shouldDeleteSuccessfullyAndRedirect() throws IOException {
            MandateMenuConfig config = new MandateMenuConfig();
            config.setMandateName("test-mandate");
            backingBean.setSelected(config);
            when(plaintextSecurity.getAllMandate()).thenReturn(Set.of("default"));
            MandateMenuConfig reloadedConfig = new MandateMenuConfig();
            reloadedConfig.setMandateName("default");
            when(service.getOrCreateConfig("default")).thenReturn(reloadedConfig);
            when(service.getAllMenuTitles()).thenReturn(List.of());

            backingBean.delete();

            verify(service).deleteConfig(config);
            // After delete, loadMandates is called which sets selected to first mandate
            verify(externalContext).redirect("mandatemenu.xhtml");
        }

        @Test
        @DisplayName("Should show error when delete throws exception")
        void shouldShowErrorWhenDeleteThrows() {
            MandateMenuConfig config = new MandateMenuConfig();
            config.setMandateName("test-mandate");
            backingBean.setSelected(config);

            doThrow(new RuntimeException("DB Error")).when(service).deleteConfig(config);

            backingBean.delete();

            verify(facesContext, atLeastOnce()).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Nested
    @DisplayName("newMandate")
    class NewMandate {

        @Test
        @DisplayName("Should create new config and redirect")
        void shouldCreateNewConfigAndRedirect() throws IOException {
            backingBean.newMandate();

            assertNotNull(backingBean.getSelected());
            verify(externalContext).redirect("mandatemenudetail.xhtml");
        }

        @Test
        @DisplayName("Should handle redirect failure")
        void shouldHandleRedirectFailure() throws IOException {
            doThrow(new IOException("Redirect failed")).when(externalContext).redirect(anyString());

            assertDoesNotThrow(() -> backingBean.newMandate());
            assertNotNull(backingBean.getSelected());
        }
    }

    @Nested
    @DisplayName("edit")
    class Edit {

        @Test
        @DisplayName("Should not throw when selected is set")
        void shouldNotThrowWhenSelectedIsSet() {
            MandateMenuConfig config = new MandateMenuConfig();
            config.setMandateName("test");
            backingBean.setSelected(config);

            assertDoesNotThrow(() -> backingBean.edit());
        }

        @Test
        @DisplayName("Should not throw when selected is null")
        void shouldNotThrowWhenSelectedIsNull() {
            backingBean.setSelected(null);

            assertDoesNotThrow(() -> backingBean.edit());
        }
    }

    @Nested
    @DisplayName("init via loadMandates")
    class Init {

        @Test
        @DisplayName("Should load mandates from security and set first as selected")
        void shouldLoadMandatesAndSetFirstAsSelected() {
            when(plaintextSecurity.getAllMandate()).thenReturn(Set.of("mandate1"));
            MandateMenuConfig config = new MandateMenuConfig();
            config.setMandateName("mandate1");
            when(service.getOrCreateConfig(anyString())).thenReturn(config);
            when(service.getAllMenuTitles()).thenReturn(List.of("Menu1", "Menu2"));

            backingBean.init();

            assertNotNull(backingBean.getSelected());
            assertFalse(backingBean.getMandates().isEmpty());
        }

        @Test
        @DisplayName("Should handle exception during mandate loading")
        void shouldHandleExceptionDuringMandateLoading() {
            when(plaintextSecurity.getAllMandate()).thenThrow(new RuntimeException("Error"));
            MandateMenuConfig defaultConfig = new MandateMenuConfig();
            defaultConfig.setMandateName("default");
            when(service.getOrCreateConfig("default")).thenReturn(defaultConfig);

            assertDoesNotThrow(() -> backingBean.init());
        }

        @Test
        @DisplayName("Should handle exception when loading individual mandate config")
        void shouldHandleExceptionForIndividualMandate() {
            when(plaintextSecurity.getAllMandate()).thenReturn(Set.of("bad-mandate"));
            when(service.getOrCreateConfig("bad-mandate")).thenThrow(new RuntimeException("DB Error"));
            when(service.getOrCreateConfig("default")).thenThrow(new RuntimeException("DB Error"));
            when(service.getAllMenuTitles()).thenReturn(List.of());

            assertDoesNotThrow(() -> backingBean.init());
        }
    }

    @Nested
    @DisplayName("toggleMode with FacesContext error")
    class ToggleModeError {

        @Test
        @DisplayName("Should add error message when toggle throws exception")
        void shouldAddErrorMessageWhenToggleThrows() {
            MandateMenuConfig config = new MandateMenuConfig();
            config.setMandateName("test");
            config.setWhitelistMode(false);
            // Setting hiddenMenus to null will cause NPE in the toggle logic
            config.setHiddenMenus(null);
            backingBean.setSelected(config);
            backingBean.setAvailableMenus(List.of("Menu1"));

            backingBean.toggleMode();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }
}
