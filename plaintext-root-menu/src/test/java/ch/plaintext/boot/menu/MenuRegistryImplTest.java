/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import ch.plaintext.MenuRegistry;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MenuRegistryImplTest {

    @InjectMocks
    private MenuRegistryImpl menuRegistry;

    @Mock
    private ApplicationContext applicationContext;

    private MenuItemImpl createItem(String title, String parent) {
        MenuItemImpl item = new MenuItemImpl();
        item.setTitle(title);
        item.setParent(parent);
        return item;
    }

    @Nested
    class GetAllMenuTitles {

        @Test
        void shouldReturnEmptyListWhenNoMenuBeans() {
            when(applicationContext.getBeansOfType(MenuItemImpl.class))
                    .thenReturn(Collections.emptyMap());

            List<String> titles = menuRegistry.getAllMenuTitles();

            assertNotNull(titles);
            assertTrue(titles.isEmpty());
        }

        @Test
        void shouldReturnTitleForRootItem() {
            MenuItemImpl item = createItem("Dashboard", "");

            Map<String, MenuItemImpl> beans = Map.of("dash", item);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            List<String> titles = menuRegistry.getAllMenuTitles();

            assertEquals(1, titles.size());
            assertEquals("Dashboard", titles.get(0));
        }

        @Test
        void shouldReturnFullTitleForChildItem() {
            MenuItemImpl item = createItem("Users", "Admin");

            Map<String, MenuItemImpl> beans = Map.of("users", item);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            List<String> titles = menuRegistry.getAllMenuTitles();

            assertEquals(1, titles.size());
            assertEquals("Admin | Users", titles.get(0));
        }

        @Test
        void shouldReturnSortedTitles() {
            MenuItemImpl itemC = createItem("Zeiterfassung", "");
            MenuItemImpl itemA = createItem("Admin", "");
            MenuItemImpl itemB = createItem("Dashboard", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("c", itemC);
            beans.put("a", itemA);
            beans.put("b", itemB);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            List<String> titles = menuRegistry.getAllMenuTitles();

            assertEquals(3, titles.size());
            assertEquals("Admin", titles.get(0));
            assertEquals("Dashboard", titles.get(1));
            assertEquals("Zeiterfassung", titles.get(2));
        }

        @Test
        void shouldReturnDistinctTitles() {
            MenuItemImpl item1 = createItem("Dashboard", "");
            MenuItemImpl item2 = createItem("Dashboard", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("d1", item1);
            beans.put("d2", item2);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            List<String> titles = menuRegistry.getAllMenuTitles();

            assertEquals(1, titles.size());
        }

        @Test
        void shouldReturnTitleOnlyWhenParentIsNull() {
            MenuItemImpl item = createItem("Home", null);

            Map<String, MenuItemImpl> beans = Map.of("home", item);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            List<String> titles = menuRegistry.getAllMenuTitles();

            assertEquals(1, titles.size());
            assertEquals("Home", titles.get(0));
        }

        @Test
        void shouldReturnTitleOnlyWhenParentIsBlank() {
            MenuItemImpl item = createItem("Home", "   ");

            Map<String, MenuItemImpl> beans = Map.of("home", item);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            List<String> titles = menuRegistry.getAllMenuTitles();

            assertEquals(1, titles.size());
            assertEquals("Home", titles.get(0));
        }

        @Test
        void shouldHandleMixOfRootAndChildItems() {
            MenuItemImpl root = createItem("Admin", "");
            MenuItemImpl child = createItem("Users", "Admin");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("admin", root);
            beans.put("users", child);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            List<String> titles = menuRegistry.getAllMenuTitles();

            assertEquals(2, titles.size());
            assertTrue(titles.contains("Admin"));
            assertTrue(titles.contains("Admin | Users"));
        }
    }

    @Nested
    class GetAllMenuItemsImpl {

        @Test
        void shouldReturnEmptyListWhenNoMenuBeans() {
            when(applicationContext.getBeansOfType(MenuItemImpl.class))
                    .thenReturn(Collections.emptyMap());

            List<MenuItemImpl> items = menuRegistry.getAllMenuItemsImpl();

            assertNotNull(items);
            assertTrue(items.isEmpty());
        }

        @Test
        void shouldReturnMenuItemImplDirectly() {
            MenuItemImpl item = createItem("Home", "");

            Map<String, MenuItemImpl> beans = Map.of("home", item);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            List<MenuItemImpl> items = menuRegistry.getAllMenuItemsImpl();

            assertEquals(1, items.size());
            assertSame(item, items.get(0));
        }

        @Test
        void shouldReturnNewListNotBackedByMap() {
            MenuItemImpl item = createItem("Home", "");

            Map<String, MenuItemImpl> beans = Map.of("home", item);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            List<MenuItemImpl> items = menuRegistry.getAllMenuItemsImpl();

            // Should be a new ArrayList, so adding to it should not throw
            assertDoesNotThrow(() -> items.add(createItem("Other", "")));
            assertEquals(2, items.size());
        }

        @Test
        void shouldReturnMultipleItems() {
            MenuItemImpl item1 = createItem("A", "");
            MenuItemImpl item2 = createItem("B", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("a", item1);
            beans.put("b", item2);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            List<MenuItemImpl> items = menuRegistry.getAllMenuItemsImpl();

            assertEquals(2, items.size());
        }
    }

    @Test
    void shouldImplementMenuRegistryInterface() {
        assertInstanceOf(MenuRegistry.class, menuRegistry);
    }
}
