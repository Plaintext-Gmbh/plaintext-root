/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.security;

import jakarta.faces.component.UIViewRoot;
import jakarta.faces.context.FacesContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for PageAccessGuardBackingBean - JSF preRenderView access check.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PageAccessGuardBackingBeanTest {

    @Mock
    private PageAccessGuardService pageAccessGuardService;

    @Mock
    private FacesContext facesContext;

    @Mock
    private UIViewRoot viewRoot;

    @InjectMocks
    private PageAccessGuardBackingBean bean;

    @Test
    void checkPageAccess_shouldDoNothing_whenFacesContextNull() throws IOException {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(null);

            bean.checkPageAccess();

            verify(pageAccessGuardService, never()).hasAccessToView(anyString());
        }
    }

    @Test
    void checkPageAccess_shouldDoNothing_whenViewRootNull() throws IOException {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            when(facesContext.getViewRoot()).thenReturn(null);

            bean.checkPageAccess();

            verify(pageAccessGuardService, never()).hasAccessToView(anyString());
        }
    }

    @Test
    void checkPageAccess_shouldAllowAccess_whenServiceReturnsTrue() throws IOException {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            when(facesContext.getViewRoot()).thenReturn(viewRoot);
            when(viewRoot.getViewId()).thenReturn("/kontakte.xhtml");
            when(pageAccessGuardService.hasAccessToView("/kontakte.xhtml")).thenReturn(true);

            bean.checkPageAccess();

            verify(pageAccessGuardService, never()).redirectToAccessDenied();
        }
    }

    @Test
    void checkPageAccess_shouldDenyAccess_whenServiceReturnsFalse() throws IOException {
        try (MockedStatic<FacesContext> mocked = mockStatic(FacesContext.class)) {
            mocked.when(FacesContext::getCurrentInstance).thenReturn(facesContext);
            when(facesContext.getViewRoot()).thenReturn(viewRoot);
            when(viewRoot.getViewId()).thenReturn("/admin.xhtml");
            when(pageAccessGuardService.hasAccessToView("/admin.xhtml")).thenReturn(false);

            bean.checkPageAccess();

            verify(pageAccessGuardService).redirectToAccessDenied();
        }
    }
}
