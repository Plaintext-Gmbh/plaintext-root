/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.boot.dashboard;

import ch.plaintext.TileRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of {@link TileRegistry} that provides access to all registered dashboard tiles
 * – analogous to {@link ch.plaintext.boot.menu.MenuRegistryImpl}.
 *
 * @author plaintext.ch
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TileRegistryImpl implements TileRegistry {

    private final ApplicationContext applicationContext;

    @Override
    public List<String> getAllTileTitles() {
        Map<String, TileItemImpl> tileBeans = applicationContext.getBeansOfType(TileItemImpl.class);
        return tileBeans.values().stream()
            .map(TileItemImpl::getTitle)
            .sorted()
            .distinct()
            .collect(Collectors.toList());
    }

    @Override
    public List<TileItem> getAllTileItems() {
        Map<String, TileItemImpl> tileBeans = applicationContext.getBeansOfType(TileItemImpl.class);
        return tileBeans.values().stream()
            .map(item -> (TileItem) item)
            .collect(Collectors.toList());
    }

    /**
     * Returns all tiles as {@link TileItemImpl} (without the interface cast).
     *
     * @return list of the tile implementations
     */
    public List<TileItemImpl> getAllTileItemsImpl() {
        Map<String, TileItemImpl> tileBeans = applicationContext.getBeansOfType(TileItemImpl.class);
        return new ArrayList<>(tileBeans.values());
    }
}
