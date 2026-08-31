/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import ch.plaintext.MenuVisibilityProvider;
import ch.plaintext.boot.menu.SecurityProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Scanner for finding classes annotated with {@link DashboardTile} – analogous to the
 * {@link ch.plaintext.boot.menu.MenuAnnotationScanner}.
 *
 * @author plaintext.ch
 */
@Slf4j
@RequiredArgsConstructor
public class TileAnnotationScanner {

    private final SecurityProvider securityProvider;
    private final MenuVisibilityProvider menuVisibilityProvider;
    private final BeanFactory beanFactory;

    public List<TileItemImpl> findAnnotatedClasses(String scanPackage) {
        List<TileItemImpl> tiles = new ArrayList<>();

        if (scanPackage == null || scanPackage.trim().isEmpty()) {
            log.warn("Scan-Package ist null oder leer, gebe leere Kachel-Liste zurück");
            return tiles;
        }

        ClassPathScanningCandidateComponentProvider provider = createComponentScanner();

        for (BeanDefinition beanDef : provider.findCandidateComponents(scanPackage)) {
            TileItemImpl tile = createTile(beanDef);
            if (tile != null) {
                tiles.add(tile);
            }
        }

        return tiles;
    }

    private ClassPathScanningCandidateComponentProvider createComponentScanner() {
        ClassPathScanningCandidateComponentProvider provider =
            new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(DashboardTile.class));
        return provider;
    }

    private TileItemImpl createTile(BeanDefinition beanDef) {
        try {
            Class<?> clazz = Class.forName(beanDef.getBeanClassName());
            DashboardTile annotation = clazz.getAnnotation(DashboardTile.class);

            TileItemImpl tile = new TileItemImpl();
            tile.setId(annotation.id());
            tile.setTitle(annotation.title());
            tile.setIcon(annotation.icon());
            tile.setImage(annotation.image());
            tile.setLink(annotation.link());
            tile.setOrder(annotation.order());
            tile.setMenuTitle(annotation.menuTitle());
            tile.setRoles(Arrays.asList(annotation.roles()));
            tile.setSecurityProvider(securityProvider);
            tile.setMenuVisibilityProvider(menuVisibilityProvider);
            tile.setBeanFactory(beanFactory);

            log.debug("Kachel gefunden: {} (id: {}, order: {})",
                tile.getTitle(), tile.getId(), tile.getOrder());

            return tile;
        } catch (Exception e) {
            log.error("Konnte Kachel nicht erzeugen aus Bean-Definition: {}", beanDef.getBeanClassName(), e);
        }
        return null;
    }
}
