/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.menu;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.primefaces.model.menu.MenuElement;
import org.primefaces.model.menu.MenuModel;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MenuModelBuilderTest {

    @InjectMocks
    private MenuModelBuilder menuModelBuilder;

    @Mock
    private ApplicationContext applicationContext;

    @Nested
    class EmptyMenu {

        @Test
        void shouldReturnEmptyMenuModelWhenNoBeansRegistered() {
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(Collections.emptyMap());

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertNotNull(model);
            assertTrue(model.getElements().isEmpty());
            verify(applicationContext, times(1)).getBeansOfType(MenuItemImpl.class);
        }

        @Test
        void shouldReturnEmptyMenuModelWhenAllItemsAreHidden() {
            MenuItemImpl hiddenItem = createVisibleMenuItem("Hidden", "", 10, "/hidden.xhtml", "", false);

            Map<String, MenuItemImpl> beans = Map.of("hidden", hiddenItem);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertNotNull(model);
            assertTrue(model.getElements().isEmpty());
        }
    }

    @Nested
    class FlatMenu {

        @Test
        void shouldBuildSingleRootMenuItem() {
            MenuItemImpl item = createMenuItem("Home", "", 10, "/home.xhtml", "pi pi-home");

            Map<String, MenuItemImpl> beans = Map.of("home", item);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(1, model.getElements().size());
            MenuElement element = model.getElements().get(0);
            assertInstanceOf(PrimefacesMenuItem.class, element);
            PrimefacesMenuItem menuItem = (PrimefacesMenuItem) element;
            assertEquals("Home", menuItem.getValue());
        }

        @Test
        void shouldBuildMultipleRootMenuItems() {
            MenuItemImpl home = createMenuItem("Home", "", 10, "/home.xhtml", "pi pi-home");
            MenuItemImpl about = createMenuItem("About", "", 20, "/about.xhtml", "pi pi-info");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("home", home);
            beans.put("about", about);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(2, model.getElements().size());
        }

        @Test
        void shouldTreatNullParentAsRoot() {
            MenuItemImpl item = createMenuItem("Home", null, 10, "/home.xhtml", "");

            Map<String, MenuItemImpl> beans = Map.of("home", item);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(1, model.getElements().size());
            assertInstanceOf(PrimefacesMenuItem.class, model.getElements().get(0));
        }

        @Test
        void shouldTreatBlankParentAsRoot() {
            MenuItemImpl item = createMenuItem("Home", "   ", 10, "/home.xhtml", "");

            Map<String, MenuItemImpl> beans = Map.of("home", item);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(1, model.getElements().size());
            assertInstanceOf(PrimefacesMenuItem.class, model.getElements().get(0));
        }
    }

    @Nested
    class HierarchicalMenu {

        @Test
        void shouldBuildParentChildHierarchy() {
            MenuItemImpl parent = createMenuItem("Admin", "", 10, "/admin.xhtml", "pi pi-cog");
            MenuItemImpl child = createMenuItem("Users", "Admin", 20, "/admin/users.xhtml", "pi pi-users");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("admin", parent);
            beans.put("users", child);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(1, model.getElements().size());
            MenuElement rootElement = model.getElements().get(0);
            assertInstanceOf(PrimefacesSubmenu.class, rootElement);

            PrimefacesSubmenu submenu = (PrimefacesSubmenu) rootElement;
            assertEquals("Admin", submenu.getLabel());
            assertEquals(1, submenu.getElements().size());
            assertInstanceOf(PrimefacesMenuItem.class, submenu.getElements().get(0));
        }

        @Test
        void shouldBuildMultipleChildrenUnderOneParent() {
            MenuItemImpl parent = createMenuItem("Admin", "", 10, "/admin.xhtml", "pi pi-cog");
            MenuItemImpl child1 = createMenuItem("Users", "Admin", 20, "/admin/users.xhtml", "pi pi-users");
            MenuItemImpl child2 = createMenuItem("Roles", "Admin", 30, "/admin/roles.xhtml", "pi pi-shield");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("admin", parent);
            beans.put("users", child1);
            beans.put("roles", child2);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(1, model.getElements().size());
            PrimefacesSubmenu submenu = (PrimefacesSubmenu) model.getElements().get(0);
            assertEquals(2, submenu.getElements().size());
        }

        @Test
        void shouldBuildNestedSubmenus() {
            MenuItemImpl root = createMenuItem("Admin", "", 10, "/admin.xhtml", "pi pi-cog");
            MenuItemImpl mid = createMenuItem("Settings", "Admin", 20, "/settings.xhtml", "pi pi-wrench");
            MenuItemImpl leaf = createMenuItem("Advanced", "Settings", 30, "/settings/advanced.xhtml", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("admin", root);
            beans.put("settings", mid);
            beans.put("advanced", leaf);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(1, model.getElements().size());
            PrimefacesSubmenu rootSubmenu = (PrimefacesSubmenu) model.getElements().get(0);
            assertEquals("Admin", rootSubmenu.getLabel());
            assertEquals(1, rootSubmenu.getElements().size());

            PrimefacesSubmenu midSubmenu = (PrimefacesSubmenu) rootSubmenu.getElements().get(0);
            assertEquals("Settings", midSubmenu.getLabel());
            assertEquals(1, midSubmenu.getElements().size());

            assertInstanceOf(PrimefacesMenuItem.class, midSubmenu.getElements().get(0));
        }

        @Test
        void shouldSkipChildrenWithMissingParent() {
            MenuItemImpl orphan = createMenuItem("Orphan", "NonExistent", 10, "/orphan.xhtml", "");

            Map<String, MenuItemImpl> beans = Map.of("orphan", orphan);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertTrue(model.getElements().isEmpty());
        }

        @Test
        void shouldHandleChildOrderLowerThanParentOrder() {
            MenuItemImpl child = createMenuItem("Eingabe", "Zeiterfassung", 10, "/eingabe.xhtml", "");
            MenuItemImpl parent = createMenuItem("Zeiterfassung", "", 100, "/zeit.xhtml", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("child", child);
            beans.put("parent", parent);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(1, model.getElements().size());
            assertInstanceOf(PrimefacesSubmenu.class, model.getElements().get(0));
        }
    }

    @Nested
    class Sorting {

        @Test
        void shouldSortMenuItemsByOrder() {
            MenuItemImpl high = createMenuItem("Second", "", 20, "/second.xhtml", "");
            MenuItemImpl low = createMenuItem("First", "", 10, "/first.xhtml", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("high", high);
            beans.put("low", low);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(2, model.getElements().size());
            PrimefacesMenuItem first = (PrimefacesMenuItem) model.getElements().get(0);
            PrimefacesMenuItem second = (PrimefacesMenuItem) model.getElements().get(1);
            assertEquals("First", first.getValue());
            assertEquals("Second", second.getValue());
        }

        @Test
        void shouldSortChildrenByOrder() {
            MenuItemImpl parent = createMenuItem("Admin", "", 10, "/admin.xhtml", "");
            MenuItemImpl child2 = createMenuItem("Roles", "Admin", 30, "/roles.xhtml", "");
            MenuItemImpl child1 = createMenuItem("Users", "Admin", 20, "/users.xhtml", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("admin", parent);
            beans.put("roles", child2);
            beans.put("users", child1);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            PrimefacesSubmenu submenu = (PrimefacesSubmenu) model.getElements().get(0);
            assertEquals(2, submenu.getElements().size());
            PrimefacesMenuItem firstChild = (PrimefacesMenuItem) submenu.getElements().get(0);
            PrimefacesMenuItem secondChild = (PrimefacesMenuItem) submenu.getElements().get(1);
            assertEquals("Users", firstChild.getValue());
            assertEquals("Roles", secondChild.getValue());
        }

        @Test
        void shouldHandleItemsWithSameOrder() {
            MenuItemImpl a = createMenuItem("Alpha", "", 10, "/a.xhtml", "");
            MenuItemImpl b = createMenuItem("Beta", "", 10, "/b.xhtml", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("a", a);
            beans.put("b", b);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(2, model.getElements().size());
        }

        @Test
        void shouldSortThreeItemsByOrder() {
            MenuItemImpl item3 = createMenuItem("Third", "", 30, "/third.xhtml", "");
            MenuItemImpl item1 = createMenuItem("First", "", 10, "/first.xhtml", "");
            MenuItemImpl item2 = createMenuItem("Second", "", 20, "/second.xhtml", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("third", item3);
            beans.put("first", item1);
            beans.put("second", item2);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(3, model.getElements().size());
            assertEquals("First", ((PrimefacesMenuItem) model.getElements().get(0)).getValue());
            assertEquals("Second", ((PrimefacesMenuItem) model.getElements().get(1)).getValue());
            assertEquals("Third", ((PrimefacesMenuItem) model.getElements().get(2)).getValue());
        }
    }

    @Nested
    class Filtering {

        @Test
        void shouldFilterOutInvisibleItems() {
            MenuItemImpl visible = createMenuItem("Visible", "", 10, "/visible.xhtml", "");
            MenuItemImpl hidden = createVisibleMenuItem("Hidden", "", 20, "/hidden.xhtml", "", false);

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("visible", visible);
            beans.put("hidden", hidden);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(1, model.getElements().size());
            PrimefacesMenuItem item = (PrimefacesMenuItem) model.getElements().get(0);
            assertEquals("Visible", item.getValue());
        }

        @Test
        void shouldNotCreateSubmenuWhenParentVisibleButAllChildrenHidden() {
            MenuItemImpl parent = createMenuItem("Admin", "", 10, "/admin.xhtml", "");
            MenuItemImpl child = createVisibleMenuItem("Users", "Admin", 20, "/users.xhtml", "", false);

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("admin", parent);
            beans.put("child", child);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            // Parent has no visible children, so it should be rendered as a plain menu item
            assertEquals(1, model.getElements().size());
            assertInstanceOf(PrimefacesMenuItem.class, model.getElements().get(0));
        }

        @Test
        void shouldFilterOutInvisibleParentAndOrphanChildren() {
            MenuItemImpl parent = createVisibleMenuItem("Admin", "", 10, "/admin.xhtml", "", false);
            MenuItemImpl child = createMenuItem("Users", "Admin", 20, "/users.xhtml", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("admin", parent);
            beans.put("child", child);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            // Parent is filtered out, child's parent "Admin" has no submenu => child is skipped
            assertTrue(model.getElements().isEmpty());
        }

        @Test
        void shouldFilterMixOfVisibleAndInvisible() {
            MenuItemImpl visible1 = createMenuItem("Home", "", 10, "/home.xhtml", "");
            MenuItemImpl invisible = createVisibleMenuItem("Hidden", "", 20, "/hidden.xhtml", "", false);
            MenuItemImpl visible2 = createMenuItem("About", "", 30, "/about.xhtml", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("home", visible1);
            beans.put("hidden", invisible);
            beans.put("about", visible2);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(2, model.getElements().size());
        }
    }

    @Nested
    class MixedScenarios {

        @Test
        void shouldHandleMixOfRootItemsAndSubmenus() {
            MenuItemImpl home = createMenuItem("Home", "", 10, "/home.xhtml", "pi pi-home");
            MenuItemImpl admin = createMenuItem("Admin", "", 20, "/admin.xhtml", "pi pi-cog");
            MenuItemImpl users = createMenuItem("Users", "Admin", 30, "/users.xhtml", "pi pi-users");
            MenuItemImpl about = createMenuItem("About", "", 40, "/about.xhtml", "pi pi-info");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("home", home);
            beans.put("admin", admin);
            beans.put("users", users);
            beans.put("about", about);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(3, model.getElements().size());
            assertInstanceOf(PrimefacesMenuItem.class, model.getElements().get(0));
            assertInstanceOf(PrimefacesSubmenu.class, model.getElements().get(1));
            assertInstanceOf(PrimefacesMenuItem.class, model.getElements().get(2));

            PrimefacesSubmenu adminSubmenu = (PrimefacesSubmenu) model.getElements().get(1);
            assertEquals("Admin", adminSubmenu.getLabel());
            assertEquals(1, adminSubmenu.getElements().size());
        }

        @Test
        void shouldHandleComplexHierarchyWithMultipleSubmenusAndMixedOrders() {
            MenuItemImpl menu1 = createMenuItem("Menu1", "", 50, "/menu1.xhtml", "");
            MenuItemImpl child1 = createMenuItem("Child1", "Menu1", 10, "/child1.xhtml", "");
            MenuItemImpl menu2 = createMenuItem("Menu2", "", 20, "/menu2.xhtml", "");
            MenuItemImpl child2 = createMenuItem("Child2", "Menu2", 100, "/child2.xhtml", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("menu1", menu1);
            beans.put("child1", child1);
            beans.put("menu2", menu2);
            beans.put("child2", child2);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(2, model.getElements().size());
            // First should be Menu2 (order 20) as a submenu, then Menu1 (order 50) as a submenu
            // Both children have lower/higher order but are placed under their respective parents
        }
    }

    @Nested
    class DuplicateTitles {

        @Test
        void shouldNotCorruptHierarchyWhenTwoSubmenusShareTitle() {
            // Two root submenus share the title "Reports". Before the dedup guard the second
            // "Reports" submenu overwrote the first in submenuMap and was added to the model
            // twice (same object at two root positions) => corrupted, non-renderable menu.
            MenuItemImpl reports1 = createMenuItem("Reports", "", 10, "/reports1.xhtml", "");
            MenuItemImpl daily = createMenuItem("Daily", "Reports", 15, "/daily.xhtml", "");
            MenuItemImpl reports2 = createMenuItem("Reports", "", 20, "/reports2.xhtml", "");
            MenuItemImpl monthly = createMenuItem("Monthly", "Reports", 25, "/monthly.xhtml", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("reports1", reports1);
            beans.put("daily", daily);
            beans.put("reports2", reports2);
            beans.put("monthly", monthly);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            // Exactly one "Reports" submenu at root (the duplicate is dropped, not added twice)
            assertEquals(1, model.getElements().size());
            MenuElement rootElement = model.getElements().get(0);
            assertInstanceOf(PrimefacesSubmenu.class, rootElement);

            PrimefacesSubmenu reportsSubmenu = (PrimefacesSubmenu) rootElement;
            assertEquals("Reports", reportsSubmenu.getLabel());

            // Both distinctly-titled children stay attached to the single surviving submenu
            assertEquals(2, reportsSubmenu.getElements().size());
            PrimefacesMenuItem first = (PrimefacesMenuItem) reportsSubmenu.getElements().get(0);
            PrimefacesMenuItem second = (PrimefacesMenuItem) reportsSubmenu.getElements().get(1);
            assertEquals("Daily", first.getValue());
            assertEquals("Monthly", second.getValue());
        }

        @Test
        void shouldKeepFirstItemWhenRootLeafTitlesCollide() {
            // Two root leaf items with the same title: keep the first (lowest order), drop the rest.
            MenuItemImpl first = createMenuItem("Home", "", 10, "/home-a.xhtml", "");
            MenuItemImpl duplicate = createMenuItem("Home", "", 20, "/home-b.xhtml", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("first", first);
            beans.put("duplicate", duplicate);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(1, model.getElements().size());
            PrimefacesMenuItem kept = (PrimefacesMenuItem) model.getElements().get(0);
            assertEquals("Home", kept.getValue());
        }

        @Test
        void shouldAttachChildToKeptParentWhenParentTitleIsDuplicated() {
            // A duplicated parent title must not leave a child orphaned/skipped.
            MenuItemImpl adminKept = createMenuItem("Admin", "", 10, "/admin1.xhtml", "");
            MenuItemImpl adminDuplicate = createMenuItem("Admin", "", 20, "/admin2.xhtml", "");
            MenuItemImpl users = createMenuItem("Users", "Admin", 30, "/users.xhtml", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("adminKept", adminKept);
            beans.put("adminDuplicate", adminDuplicate);
            beans.put("users", users);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(1, model.getElements().size());
            PrimefacesSubmenu adminSubmenu = (PrimefacesSubmenu) model.getElements().get(0);
            assertEquals("Admin", adminSubmenu.getLabel());
            assertEquals(1, adminSubmenu.getElements().size());
            assertEquals("Users", ((PrimefacesMenuItem) adminSubmenu.getElements().get(0)).getValue());
        }

        @Test
        void shouldKeepSameLeafTitleUnderDifferentParents() {
            // Regression: a leaf title shared across DIFFERENT parents (each pointing at its own
            // page) must NOT be dropped. The previous global-title dedup silently hid the second
            // page from the menu (e.g. "Karte" existed under both "Wanderungen" and "Lauftage").
            MenuItemImpl wanderungen = createMenuItem("Wanderungen", "", 10, "/wanderungen.xhtml", "");
            MenuItemImpl wanderKarte = createMenuItem("Karte", "Wanderungen", 20, "/wander-karte.xhtml", "");
            MenuItemImpl lauftage = createMenuItem("Lauftage", "", 30, "/lauftage.xhtml", "");
            MenuItemImpl bielerKarte = createMenuItem("Karte", "Lauftage", 40, "/bieler-map.xhtml", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("wanderungen", wanderungen);
            beans.put("wanderKarte", wanderKarte);
            beans.put("lauftage", lauftage);
            beans.put("bielerKarte", bielerKarte);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertEquals(2, model.getElements().size());
            PrimefacesSubmenu wanderungenSubmenu = (PrimefacesSubmenu) model.getElements().get(0);
            PrimefacesSubmenu lauftageSubmenu = (PrimefacesSubmenu) model.getElements().get(1);
            assertEquals("Wanderungen", wanderungenSubmenu.getLabel());
            assertEquals("Lauftage", lauftageSubmenu.getLabel());
            // Each parent keeps its own "Karte" leaf pointing at its own page - neither is dropped.
            assertEquals(1, wanderungenSubmenu.getElements().size());
            assertEquals(1, lauftageSubmenu.getElements().size());
            assertEquals("Karte", ((PrimefacesMenuItem) wanderungenSubmenu.getElements().get(0)).getValue());
            assertEquals("Karte", ((PrimefacesMenuItem) lauftageSubmenu.getElements().get(0)).getValue());
        }
    }

    /**
     * Card 521: a child without an attachable parent container is skipped — the question is
     * whether that is worth a warning.
     *
     * <p>In plaintext-app eight such entries produced <b>1218 WARN lines per day, 79 % of all
     * warnings of the application</b> (Graylog, 24 h). And that was to be expected:
     * {@code API Token} and {@code Benachrichtigungen} declare
     * {@code roles={"USER","ADMIN","ROOT"}} but hang under {@code Admin} resp. {@code Root} — for
     * a normal user the parent menu is invisible and the child is visible. A warning that is
     * always there stops being read; real warnings drown in it.
     *
     * <p>The two cases below are each other's control: if the distinction were not there, one of
     * the two would have to fail.
     */
    @Nested
    class ParentNotFoundLogLevel {

        private ListAppender<ILoggingEvent> logs;
        private Logger logger;
        private Level vorherigesLevel;

        @BeforeEach
        void hoereMit() {
            logger = (Logger) LoggerFactory.getLogger(MenuModelBuilder.class);
            vorherigesLevel = logger.getLevel();
            logger.setLevel(Level.DEBUG);
            logs = new ListAppender<>();
            logs.start();
            logger.addAppender(logs);
        }

        @AfterEach
        void hoerAuf() {
            logger.detachAppender(logs);
            logs.stop();
            logger.setLevel(vorherigesLevel);
        }

        @Test
        void shouldLogDebugNotWarnWhenParentExistsButIsInvisible() {
            // The textbook case from PROD: parent menu for ADMIN only, child for everyone.
            MenuItemImpl admin = createVisibleMenuItem("Admin", "", 2, "/index.xhtml", "", false);
            MenuItemImpl apiToken = createMenuItem("API Token", "Admin", 90, "/api-token.xhtml", "");

            Map<String, MenuItemImpl> beans = new LinkedHashMap<>();
            beans.put("admin", admin);
            beans.put("apiToken", apiToken);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertTrue(model.getElements().isEmpty(),
                    "Ein Kind ohne sichtbaren Elterncontainer bleibt uebersprungen — das Verhalten "
                            + "aendert sich nicht, nur die Meldung.");
            assertTrue(meldungen(Level.WARN).isEmpty(),
                    "Der erwartete Fall darf keine Warnung erzeugen, war: " + meldungen(Level.WARN));
            assertTrue(meldungen(Level.DEBUG).stream().anyMatch(m -> m.contains("Admin") && m.contains("API Token")),
                    "Nachvollziehbar bleiben muss es trotzdem — auf DEBUG. Gesehen: " + meldungen(Level.DEBUG));
        }

        @Test
        void shouldStillWarnWhenParentDoesNotExistAtAll() {
            // The genuine configuration error: typo in parent. Reachable for NOBODY.
            MenuItemImpl orphan = createMenuItem("Kontaktliste", "Kontakteee", 10, "/kontakte.xhtml", "");

            Map<String, MenuItemImpl> beans = Map.of("orphan", orphan);
            when(applicationContext.getBeansOfType(MenuItemImpl.class)).thenReturn(beans);

            MenuModel model = menuModelBuilder.buildMenuModel();

            assertTrue(model.getElements().isEmpty());
            assertTrue(meldungen(Level.WARN).stream().anyMatch(m -> m.contains("Kontakteee") && m.contains("does not exist")),
                    "Ein parent, den es nirgends gibt, muss weiterhin als Warnung herausgehen — "
                            + "sonst verschwindet mit dem Laerm auch der echte Fehler. Gesehen: "
                            + meldungen(Level.WARN));
        }

        private List<String> meldungen(Level level) {
            return logs.list.stream()
                    .filter(e -> e.getLevel() == level)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }

    private MenuItemImpl createMenuItem(String title, String parent, int order, String command, String icon) {
        MenuItemImpl item = new MenuItemImpl();
        item.setTitle(title);
        item.setParent(parent);
        item.setOrder(order);
        item.setCommand(command);
        item.setIcon(icon);
        return item;
    }

    /**
     * Creates a MenuItemImpl where isOn() is controlled via spy.
     * Used for testing filtering of invisible items without needing real SecurityProvider setup.
     */
    private MenuItemImpl createVisibleMenuItem(String title, String parent, int order, String command, String icon, boolean visible) {
        MenuItemImpl item = new MenuItemImpl();
        item.setTitle(title);
        item.setParent(parent);
        item.setOrder(order);
        item.setCommand(command);
        item.setIcon(icon);
        item.setRoles(Collections.emptyList());

        MenuItemImpl spied = spy(item);
        when(spied.isOn()).thenReturn(visible);
        return spied;
    }
}
