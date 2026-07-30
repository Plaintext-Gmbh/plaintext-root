/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */
package ch.plaintext.mermaid;

import java.util.List;

/**
 * Service interface for generating Mermaid diagram markup.
 * Provides methods to create flowcharts from structured node and edge data.
 */
public interface IMermaidService {

    /**
     * Generates a Mermaid flowchart diagram definition from the given nodes and edges.
     *
     * @param direction the flowchart direction (e.g. "TB" for top-to-bottom, "LR" for left-to-right)
     * @param nodes     the list of nodes in the flowchart
     * @param edges     the list of edges connecting nodes
     * @return the Mermaid flowchart markup string
     */
    String generateFlowchart(String direction, List<FlowNode> nodes, List<FlowEdge> edges);

    /**
     * Represents a node in a Mermaid flowchart.
     *
     * @param id    the unique identifier of the node
     * @param label the display label of the node
     * @param style the visual style of the node
     */
    record FlowNode(
            String id,
            String label,
            NodeStyle style
    ) {}

    /**
     * Represents a directed edge between two nodes in a Mermaid flowchart.
     *
     * @param fromId the ID of the source node
     * @param toId   the ID of the target node
     * @param label  the label displayed on the edge, or null for no label
     */
    record FlowEdge(
            String fromId,
            String toId,
            String label
    ) {}

    /**
     * Visual styles for flowchart nodes, indicating the direction of data flow.
     */
    enum NodeStyle {
        /** Style for nodes representing incoming data or connections. */
        INCOMING,
        /** Style for nodes representing outgoing data or connections. */
        OUTGOING,
        /** Style for neutral nodes with no specific direction. */
        NEUTRAL,
        /** Default node style with no special highlighting. */
        DEFAULT
    }
}
