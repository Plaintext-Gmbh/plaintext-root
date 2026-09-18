/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.watch;

import ch.plaintext.modules.ModuleDescriptor;
import ch.plaintext.watch.entity.WatchUserState;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Registers the watch module with the central module administration — without it the module
 * would not appear under "Root | Module", could not be switched off, and
 * {@code ModuleDataService.export()} would reject it as unknown.
 */
@Component
public class WatchModuleDescriptor implements ModuleDescriptor {

    @Override
    public String moduleId() {
        return "watch";
    }

    @Override
    public String displayName() {
        return "Watch";
    }

    @Override
    public List<Class<?>> entities() {
        return List.of(WatchUserState.class);
    }
}
