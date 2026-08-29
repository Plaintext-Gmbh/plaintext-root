/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.web;

import ch.plaintext.boot.menu.MenuModelBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primefaces.model.menu.DefaultMenuModel;
import org.primefaces.model.menu.MenuModel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for MenuBean - view-scoped menu model backing bean.
 */
@ExtendWith(MockitoExtension.class)
class MenuBeanTest {

    @Mock
    private MenuModelBuilder menuModelBuilder;

    @InjectMocks
    private MenuBean menuBean;

    @Test
    void init_shouldBuildMenuModel() {
        MenuModel model = new DefaultMenuModel();
        when(menuModelBuilder.buildMenuModel()).thenReturn(model);

        menuBean.init();

        assertNotNull(menuBean.getModel());
        assertSame(model, menuBean.getModel());
        verify(menuModelBuilder).buildMenuModel();
    }

    @Test
    void rebuildMenu_shouldRebuildMenuModel() {
        MenuModel model1 = new DefaultMenuModel();
        MenuModel model2 = new DefaultMenuModel();
        when(menuModelBuilder.buildMenuModel()).thenReturn(model1).thenReturn(model2);

        menuBean.init();
        assertSame(model1, menuBean.getModel());

        menuBean.rebuildMenu();
        assertSame(model2, menuBean.getModel());

        verify(menuModelBuilder, times(2)).buildMenuModel();
    }
}
