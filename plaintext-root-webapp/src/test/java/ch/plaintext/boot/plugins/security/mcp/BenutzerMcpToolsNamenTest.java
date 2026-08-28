/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.boot.plugins.security.mcp;

import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Die nach aussen sichtbaren MCP-Werkzeugnamen von {@link BenutzerMcpTools} sind ein Vertrag: Ein
 * Client ruft {@code import_benutzer} auf, nicht {@code importBenutzer}. Ein vergessenes
 * {@code name = "…"} benennt das Werkzeug still um — das faellt weder beim Kompilieren noch in
 * einem fachlichen Test auf, sondern erst, wenn der Client es nicht mehr findet.
 *
 * <p>Nach dem Muster von {@code ApiTokenMcpToolsNamenTest} (Karte 489). Die Namensregel ist die des
 * {@code SyncMcpToolProvider}: gesetzter {@code name} gewinnt, sonst der Methodenname.
 */
class BenutzerMcpToolsNamenTest {

    /** Die ausgelieferten Werkzeugnamen, Stand 28.08.2026. */
    private static final Set<String> ERWARTETE_TOOLS = Set.of(
            "list_benutzer",
            "export_benutzer",
            "import_benutzer"
    );

    private static String toolName(Method m) {
        McpTool a = m.getAnnotation(McpTool.class);
        String name = a.name();
        return name != null && !name.isBlank() ? name : m.getName();
    }

    private static Set<String> ausgelieferteTools() {
        Set<String> namen = new TreeSet<>();
        for (Method m : BenutzerMcpTools.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(McpTool.class)) {
                namen.add(toolName(m));
            }
        }
        return namen;
    }

    @Test
    void keinToolNameHatSichGeaendert() {
        Set<String> ist = ausgelieferteTools();
        Set<String> fehlend = new TreeSet<>(ERWARTETE_TOOLS);
        fehlend.removeAll(ist);
        Set<String> neu = new TreeSet<>(ist);
        neu.removeAll(ERWARTETE_TOOLS);
        assertTrue(fehlend.isEmpty(),
                "Diese MCP-Werkzeuge werden NICHT MEHR ausgeliefert — jeder Client, der sie aufruft, "
                        + "findet sie nicht mehr:\n  " + String.join("\n  ", fehlend));
        assertTrue(neu.isEmpty(),
                "Diese Werkzeugnamen sind neu:\n  " + String.join("\n  ", neu)
                        + "\nIst das gewollt, gehoert der Name in ERWARTETE_TOOLS; sonst wurde ein "
                        + "Werkzeug still umbenannt. Und: mindestens.werkzeuge in "
                        + "mcp-scope-vertrag.properties mitziehen.");
    }

    /** Gegenprobe: Ohne sie waere der Test auch gruen, wenn die Reflection gar nichts sieht. */
    @Test
    void diePruefungSiehtUeberhauptTools() {
        assertEquals(3, ausgelieferteTools().size(),
                "Die Anzahl der @McpTool-Methoden hat sich geaendert.");
    }
}
