/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.plugins.security.web;

import ch.plaintext.PlaintextSecurity;
import ch.plaintext.boot.plugins.security.model.MyUserEntity;
import ch.plaintext.boot.plugins.security.persistence.MyUserRepository;
import ch.plaintext.menuesteuerung.model.MandateMenuConfig;
import ch.plaintext.menuesteuerung.persistence.MandateMenuConfigRepository;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for MandateBackingBean - mandate management UI bean.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MandateBackingBeanTest {

    @Mock
    private PlaintextSecurity plaintextSecurity;

    @Mock
    private MyUserRepository userRepository;

    @Mock
    private MandateMenuConfigRepository mandateMenuConfigRepository;

    @Mock
    private FacesContext facesContext;

    @InjectMocks
    private MandateBackingBean bean;

    @BeforeEach
    void setUp() {
        when(plaintextSecurity.getAllMandate()).thenReturn(new LinkedHashSet<>(Arrays.asList("default", "dev")));
        when(userRepository.findAll()).thenReturn(new ArrayList<>());
    }

    @Test
    void init_shouldCallReload() {
        bean.init();

        verify(plaintextSecurity).getAllMandate();
        verify(userRepository).findAll();
    }

    @Test
    void reload_shouldLoadMandateAndUsers() {
        bean.reload();

        assertNotNull(bean.getMandate());
        assertNotNull(bean.getUsers());
        verify(plaintextSecurity).getAllMandate();
        verify(userRepository).findAll();
    }

    @Test
    void reload_shouldSetFirstMandateAsSelected_whenNoneSelected() {
        bean.setSelectedMandat(null);

        bean.reload();

        assertNotNull(bean.getSelectedMandat());
    }

    @Test
    void reload_shouldPreservePreviousMandate() {
        bean.reload();
        // After initial load, add a custom mandate
        bean.getMandate().add("custom");

        // Reload - custom should be preserved if not in getAllMandate
        when(plaintextSecurity.getAllMandate()).thenReturn(new LinkedHashSet<>(Arrays.asList("default")));
        bean.reload();

        assertTrue(bean.getMandate().contains("custom"));
    }

    @Test
    void selectMandat_shouldSetSelectedMandat() {
        bean.setSelectedMandat("dev");
        bean.selectMandat();
        assertEquals("dev", bean.getSelectedMandat());
    }

    @Test
    void createMandat_shouldAddNewMandat() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.reload();
            bean.setNewMandatName("newMandat");
            when(mandateMenuConfigRepository.existsByMandateName("newmandat")).thenReturn(false);
            when(mandateMenuConfigRepository.save(any(MandateMenuConfig.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            bean.createMandat();

            verify(mandateMenuConfigRepository).save(any(MandateMenuConfig.class));
            assertEquals("", bean.getNewMandatName());
        }
    }

    @Test
    void createMandat_shouldShowError_whenNameIsNull() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.setNewMandatName(null);
            bean.createMandat();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
            verify(mandateMenuConfigRepository, never()).save(any());
        }
    }

    @Test
    void createMandat_shouldShowError_whenNameIsEmpty() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.setNewMandatName("   ");
            bean.createMandat();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Test
    void createMandat_shouldShowError_whenMandateAlreadyExistsInList() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.reload();
            bean.setNewMandatName("default");

            bean.createMandat();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
            verify(mandateMenuConfigRepository, never()).save(any());
        }
    }

    @Test
    void createMandat_shouldShowError_whenMandateAlreadyExistsInDB() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.reload();
            bean.setNewMandatName("existingInDb");
            when(mandateMenuConfigRepository.existsByMandateName("existingindb")).thenReturn(true);

            bean.createMandat();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
            verify(mandateMenuConfigRepository, never()).save(any(MandateMenuConfig.class));
        }
    }

    @Test
    void createMandat_shouldShowError_whenExceptionOccurs() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.reload();
            bean.setNewMandatName("failMandat");
            when(mandateMenuConfigRepository.existsByMandateName("failmandat")).thenReturn(false);
            when(mandateMenuConfigRepository.save(any())).thenThrow(new RuntimeException("DB error"));

            bean.createMandat();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Test
    void saveUserMandat_shouldSaveUser() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            MyUserEntity user = new MyUserEntity();
            user.setId(1L);
            user.setUsername("test@example.com");
            user.setMandat("dev");
            when(plaintextSecurity.getId()).thenReturn(2L);

            bean.saveUserMandat(user);

            verify(userRepository).save(user);
            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_INFO));
        }
    }

    @Test
    void saveUserMandat_shouldReloadMandate_whenCurrentUser() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            MyUserEntity user = new MyUserEntity();
            user.setId(1L);
            user.setUsername("test@example.com");
            when(plaintextSecurity.getId()).thenReturn(1L);

            bean.saveUserMandat(user);

            verify(userRepository).save(user);
            // Should reload mandates since current user's mandate changed
            verify(plaintextSecurity, atLeastOnce()).getAllMandate();
        }
    }

    @Test
    void saveUserMandat_shouldShowError_onException() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            MyUserEntity user = new MyUserEntity();
            user.setId(1L);
            when(userRepository.save(any())).thenThrow(new RuntimeException("DB error"));

            bean.saveUserMandat(user);

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Test
    void deleteMandat_shouldShowError_whenNoneSelected() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.setSelectedMandat(null);
            bean.deleteMandat();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Test
    void deleteMandat_shouldShowError_whenDeletingDefault() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.setSelectedMandat("default");
            bean.deleteMandat();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Test
    void deleteMandat_shouldShowError_whenUsersAssigned() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            MyUserEntity user = new MyUserEntity();
            user.setMandat("dev");
            when(userRepository.findAll()).thenReturn(List.of(user));
            bean.reload();
            bean.setSelectedMandat("dev");

            bean.deleteMandat();

            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_ERROR));
        }
    }

    @Test
    void deleteMandat_shouldRemoveMandat_whenNoUsersAssigned() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.reload();
            bean.setSelectedMandat("dev");

            bean.deleteMandat();

            assertFalse(bean.getMandate().contains("dev"));
            verify(facesContext).addMessage(isNull(), argThat(msg ->
                    msg.getSeverity() == FacesMessage.SEVERITY_INFO));
        }
    }

    @Test
    void deleteMandat_shouldSelectFirstRemaining_afterDeletion() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            bean.reload();
            bean.setSelectedMandat("dev");

            bean.deleteMandat();

            assertNotNull(bean.getSelectedMandat());
        }
    }

    @Test
    void deleteMandat_shouldSetNullSelected_whenListEmpty() {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);

            when(plaintextSecurity.getAllMandate()).thenReturn(new LinkedHashSet<>(Arrays.asList("onlyone")));
            bean.reload();
            bean.setSelectedMandat("onlyone");

            bean.deleteMandat();

            assertNull(bean.getSelectedMandat());
        }
    }

    @Test
    void getAllMandate_shouldReturnSortedCopy() {
        when(plaintextSecurity.getAllMandate()).thenReturn(new LinkedHashSet<>(Arrays.asList("zeta", "alpha", "beta")));
        bean.reload();

        List<String> result = bean.getAllMandate();

        assertEquals("alpha", result.get(0));
        assertEquals("beta", result.get(1));
        assertEquals("zeta", result.get(2));
    }

    @Test
    void getAllMandate_shouldReturnCopy_notOriginalList() {
        bean.reload();

        List<String> result1 = bean.getAllMandate();
        List<String> result2 = bean.getAllMandate();

        assertNotSame(result1, result2);
    }

    @Test
    void filteredUsers_getterSetter() {
        List<MyUserEntity> filtered = new ArrayList<>();
        bean.setFilteredUsers(filtered);
        assertSame(filtered, bean.getFilteredUsers());
    }
}
