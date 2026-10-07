package com.example.gsb.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TopologyTest {

    @Test
    void registersNodesWithRackAndZone() {
        Topology topology = TestTopologies.grid(2, 2, 2);

        assertThat(topology.size()).isEqualTo(8);
        assertThat(topology.nodesByRack()).containsOnlyKeys("rack1", "rack2");
        assertThat(topology.nodesByRack().get("rack1")).hasSize(4);
        assertThat(topology.zonesByRack().get("rack1")).containsExactlyInAnyOrder("zone1", "zone2");
        assertThat(topology.nodesByRackAndZone().get("rack2").get("zone2")).hasSize(2);
    }

    @Test
    void rejectsDuplicateNodeId() {
        Topology topology = new Topology();
        topology.addNode(new Node("n1", "rack1", "zone1"));

        assertThatThrownBy(() -> topology.addNode(new Node("n1", "rack2", "zone9")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate node id");
    }

    @Test
    void removesNodes() {
        Topology topology = TestTopologies.grid(1, 1, 2);
        topology.removeNode("n1");

        assertThat(topology.size()).isEqualTo(1);
        assertThat(topology.contains("n1")).isFalse();
        assertThatThrownBy(() -> topology.removeNode("n1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown node id");
    }

    @Test
    void rejectsBlankAttributes() {
        assertThatThrownBy(() -> new Node("", "rack1", "zone1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Node("n1", " ", "zone1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Node("n1", "rack1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
