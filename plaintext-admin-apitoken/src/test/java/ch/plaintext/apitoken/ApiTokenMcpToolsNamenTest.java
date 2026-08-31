/*
 * Copyright (C) plaintext.ch, 2026.
 */
package ch.plaintext.apitoken;

import org.junit.jupiter.api.Test;
import org.springaicommunity.mcp.annotation.McpTool;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Card 489: The externally visible MCP tool names of {@link ApiTokenMcpTools} are a contract —
 * they must NOT change when the Java methods are renamed to camelCase.
 *
 * <p><b>Why this test was written before the rename:</b> A forgotten {@code name = "…"}
 * silently renames a tool that is in production. The error shows up neither at compile time nor in
 * a functional test — it shows up when a client can no longer find the tool. The list below
 * is the state BEFORE the rename; if the test stays green afterwards, it is proven that no name
 * was lost.
 *
 * <p>The naming rule is the same one that {@code SyncMcpToolProvider} from
 * {@code mcp-annotations 0.9.0} applies: {@code hasText(annotation.name()) ? name() :
 * method.getName()}.
 */
class ApiTokenMcpToolsNamenTest {

    /** The tool names as shipped, state of 03.08.2026 before the rename (card 489). */
    private static final Set<String> ERWARTETE_TOOLS = Set.of(
            "create_api_token",
            "list_api_tokens",
            "revoke_api_token"
    );

    /** Mirrors the provider's rule: a set {@code name} wins, otherwise the method name. */
    private static String toolName(Method m) {
        McpTool a = m.getAnnotation(McpTool.class);
        String name = a.name();
        return name != null && !name.isBlank() ? name : m.getName();
    }

    private static Set<String> ausgelieferteTools() {
        Set<String> namen = new TreeSet<>();
        for (Method m : ApiTokenMcpTools.class.getDeclaredMethods()) {
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

    /** Counter-check: without it the test would be green even if the reflection saw nothing at all. */
    @Test
    void diePruefungSiehtUeberhauptTools() {
        assertEquals(3, ausgelieferteTools().size(),
                "Die Anzahl der @McpTool-Methoden hat sich geaendert.");
    }

    /** The purpose of the rename (java:S100): otherwise every touch turns the quality gate red. */
    @Test
    void methodennamenFolgenDerJavaKonvention() {
        Set<String> verstoesse = new TreeSet<>();
        for (Method m : ApiTokenMcpTools.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(McpTool.class) && !m.getName().matches("^[a-z][a-zA-Z0-9]*$")) {
                verstoesse.add(m.getName());
            }
        }
        assertTrue(verstoesse.isEmpty(),
                "Diese Methodennamen verletzen java:S100:\n  " + String.join("\n  ", verstoesse)
                        + "\nUmbenennen und dabei name = \"<alter_name>\" setzen (Karte 489).");
    }
}
