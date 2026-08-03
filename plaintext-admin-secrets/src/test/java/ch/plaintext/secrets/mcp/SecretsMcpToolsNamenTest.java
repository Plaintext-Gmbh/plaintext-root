/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.secrets.mcp;

import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Karte 489: Die nach aussen sichtbaren MCP-Tool-Namen von {@link SecretsMcpTools} sind ein Vertrag —
 * sie duerfen sich beim Umbenennen der Java-Methoden auf camelCase NICHT aendern.
 *
 * <p><b>Warum dieser Test vor der Umbenennung entstand:</b> Ein vergessenes {@code name = "…"}
 * benennt ein produktives Tool still um. Der Fehler faellt weder beim Kompilieren noch in einem
 * fachlichen Test auf — er faellt auf, wenn ein Client das Tool nicht mehr findet. Die Liste unten
 * ist der Stand VOR der Umbenennung; bleibt der Test danach gruen, ist bewiesen, dass kein Name
 * verlorenging.
 *
 * <p>Die Namensregel ist dieselbe, die {@code SyncMcpToolProvider} aus
 * {@code mcp-annotations 0.9.0} anwendet: {@code hasText(annotation.name()) ? name() :
 * method.getName()}.
 */
class SecretsMcpToolsNamenTest {

    /** Die ausgelieferten Tool-Namen, Stand 03.08.2026 vor der Umbenennung (Karte 489). */
    private static final Set<String> ERWARTETE_TOOLS = Set.of(
            "set_secret"
    );

    /** Bildet die Regel des Providers nach: gesetzter {@code name} gewinnt, sonst der Methodenname. */
    private static String toolName(Method m) {
        McpTool a = m.getAnnotation(McpTool.class);
        String name = a.name();
        return name != null && !name.isBlank() ? name : m.getName();
    }

    private static Set<String> ausgelieferteTools() {
        Set<String> namen = new TreeSet<>();
        for (Method m : SecretsMcpTools.class.getDeclaredMethods()) {
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
                "Diese MCP-Tools werden NICHT MEHR ausgeliefert — jeder Client, der sie aufruft, "
                        + "findet sie nicht mehr:\n  " + String.join("\n  ", fehlend)
                        + "\nVermutlich fehlt beim Umbenennen ein name = \"…\" (Karte 489).");
        assertTrue(neu.isEmpty(),
                "Diese Tool-Namen sind neu:\n  " + String.join("\n  ", neu)
                        + "\nIst das gewollt, gehoert der Name in ERWARTETE_TOOLS; sonst wurde ein "
                        + "Tool still umbenannt.");
    }

    /** Gegenprobe: Ohne sie waere der Test auch gruen, wenn die Reflection gar nichts sieht. */
    @Test
    void diePruefungSiehtUeberhauptTools() {
        assertEquals(1, ausgelieferteTools().size(),
                "Die Anzahl der @McpTool-Methoden hat sich geaendert.");
    }

    /** Der Zweck der Umbenennung (java:S100): sonst faerbt jede Beruehrung das Quality-Gate rot. */
    @Test
    void methodennamenFolgenDerJavaKonvention() {
        Set<String> verstoesse = new TreeSet<>();
        for (Method m : SecretsMcpTools.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(McpTool.class) && !m.getName().matches("^[a-z][a-zA-Z0-9]*$")) {
                verstoesse.add(m.getName());
            }
        }
        assertTrue(verstoesse.isEmpty(),
                "Diese Methodennamen verletzen java:S100:\n  " + String.join("\n  ", verstoesse)
                        + "\nUmbenennen und dabei name = \"<alter_name>\" setzen (Karte 489).");
    }
}
