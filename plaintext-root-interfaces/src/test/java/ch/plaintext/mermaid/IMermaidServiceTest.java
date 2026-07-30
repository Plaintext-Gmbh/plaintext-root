/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mermaid;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IMermaidServiceTest {

    @Test
    void nodeStyle_hasAllExpectedValues() {
        IMermaidService.NodeStyle[] values = IMermaidService.NodeStyle.values();
        assertEquals(4, values.length);
    }

    @Test
    void nodeStyle_incoming_exists() {
        assertEquals(IMermaidService.NodeStyle.INCOMING, IMermaidService.NodeStyle.valueOf("INCOMING"));
    }

    @Test
    void nodeStyle_outgoing_exists() {
        assertEquals(IMermaidService.NodeStyle.OUTGOING, IMermaidService.NodeStyle.valueOf("OUTGOING"));
    }

    @Test
    void nodeStyle_neutral_exists() {
        assertEquals(IMermaidService.NodeStyle.NEUTRAL, IMermaidService.NodeStyle.valueOf("NEUTRAL"));
    }

    @Test
    void nodeStyle_default_exists() {
        assertEquals(IMermaidService.NodeStyle.DEFAULT, IMermaidService.NodeStyle.valueOf("DEFAULT"));
    }

    @Test
    void nodeStyle_invalidValue_throwsException() {
        assertThrows(IllegalArgumentException.class,
                () -> IMermaidService.NodeStyle.valueOf("INVALID"));
    }

    @Test
    void flowNode_recordAccessors() {
        IMermaidService.FlowNode node = new IMermaidService.FlowNode("id1", "Label", IMermaidService.NodeStyle.INCOMING);

        assertEquals("id1", node.id());
        assertEquals("Label", node.label());
        assertEquals(IMermaidService.NodeStyle.INCOMING, node.style());
    }

    @Test
    void flowNode_equalsAndHashCode() {
        IMermaidService.FlowNode n1 = new IMermaidService.FlowNode("a", "A", IMermaidService.NodeStyle.DEFAULT);
        IMermaidService.FlowNode n2 = new IMermaidService.FlowNode("a", "A", IMermaidService.NodeStyle.DEFAULT);

        assertEquals(n1, n2);
        assertEquals(n1.hashCode(), n2.hashCode());
    }

    @Test
    void flowNode_notEquals() {
        IMermaidService.FlowNode n1 = new IMermaidService.FlowNode("a", "A", IMermaidService.NodeStyle.DEFAULT);
        IMermaidService.FlowNode n2 = new IMermaidService.FlowNode("b", "B", IMermaidService.NodeStyle.INCOMING);

        assertNotEquals(n1, n2);
    }

    @Test
    void flowEdge_recordAccessors() {
        IMermaidService.FlowEdge edge = new IMermaidService.FlowEdge("from", "to", "connects");

        assertEquals("from", edge.fromId());
        assertEquals("to", edge.toId());
        assertEquals("connects", edge.label());
    }

    @Test
    void flowEdge_equalsAndHashCode() {
        IMermaidService.FlowEdge e1 = new IMermaidService.FlowEdge("a", "b", "label");
        IMermaidService.FlowEdge e2 = new IMermaidService.FlowEdge("a", "b", "label");

        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    void flowEdge_nullLabel() {
        IMermaidService.FlowEdge edge = new IMermaidService.FlowEdge("a", "b", null);

        assertNull(edge.label());
    }

    @Test
    void flowNode_toString_containsValues() {
        IMermaidService.FlowNode node = new IMermaidService.FlowNode("id", "Label", IMermaidService.NodeStyle.NEUTRAL);
        String str = node.toString();
        assertTrue(str.contains("id"));
        assertTrue(str.contains("Label"));
        assertTrue(str.contains("NEUTRAL"));
    }
}
