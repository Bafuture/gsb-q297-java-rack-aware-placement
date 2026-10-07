package com.example.gsb.placement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 跨机架分散与机架内可用域分散。 */
class CrossRackSpreadTest {

    @Test
    void spreadsReplicasAcrossDistinctRacksWhenPossible() {
        ReplicaPlacementManager manager =
                TestClusters.cluster(PlacementPolicy.of(3, 2, 0.2), 3, 2, 2);

        Placement placement = manager.place("group-1");
        Map<String, Node> nodes = TestClusters.nodesById(manager);

        assertThat(placement.nodeIds()).hasSize(3).doesNotHaveDuplicates();
        assertThat(placement.nodeIds().stream()
                .map(id -> nodes.get(id).rackId())
                .toList())
                .containsExactlyInAnyOrder("rack-1", "rack-2", "rack-3");
    }

    @Test
    void spreadsAcrossZonesWithinRackWhenRacksAreInsufficient() {
        // 2 个机架 × 2 个可用域，副本数 4 > 机架数 2，退化为机架内跨可用域分散
        ReplicaPlacementManager manager =
                TestClusters.cluster(PlacementPolicy.of(4, 2, 0.2), 2, 2, 1);

        Placement placement = manager.place("group-1");
        Map<String, Node> nodes = TestClusters.nodesById(manager);

        Map<String, List<String>> zonesByRack = new LinkedHashMap<>();
        for (String id : placement.nodeIds()) {
            Node node = nodes.get(id);
            zonesByRack.computeIfAbsent(node.rackId(), k -> new ArrayList<>()).add(node.zoneId());
        }
        assertThat(zonesByRack).containsOnlyKeys("rack-1", "rack-2");
        zonesByRack.forEach((rack, zones) ->
                assertThat(zones).as("rack %s zones", rack).hasSize(2).doesNotHaveDuplicates());
    }

    @Test
    void usesOnlyAsManyRacksAsReplicas() {
        ReplicaPlacementManager manager =
                TestClusters.cluster(PlacementPolicy.of(2, 1, 0.2), 3, 1, 1);

        Placement placement = manager.place("group-1");
        Map<String, Node> nodes = TestClusters.nodesById(manager);

        assertThat(placement.nodeIds().stream()
                .map(id -> nodes.get(id).rackId())
                .distinct())
                .hasSize(2);
    }

    @Test
    void everyPlacedGroupKeepsOneReplicaPerRackAcrossManyPlacements() {
        ReplicaPlacementManager manager =
                TestClusters.cluster(PlacementPolicy.of(3, 2, 0.2), 3, 2, 2);
        for (int i = 0; i < 100; i++) {
            manager.place("group-" + i);
        }
        Map<String, Node> nodes = TestClusters.nodesById(manager);
        assertThat(manager.placements()).allSatisfy(placement ->
                assertThat(placement.nodeIds().stream()
                        .map(id -> nodes.get(id).rackId())
                        .distinct())
                        .as("group %s racks", placement.groupId())
                        .hasSize(3));
    }
}
