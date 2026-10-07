package com.example.gsb.placement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

class RackSpreadPlacementTest {

    /** 副本数等于机架数时，每个副本位于不同机架。 */
    @Test
    void spreadsOneReplicaPerRack() {
        Topology topology = TestTopologies.grid(3, 2, 2);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 2, 0.2));

        PlacementPlan plan = engine.placeAll(TestTopologies.blocks("b", 30, 10));

        for (List<String> nodes : plan.blockToNodes().values()) {
            Map<String, Long> perRack = countByRack(topology, nodes);
            assertThat(perRack).hasSize(3);
            perRack.values().forEach(count -> assertThat(count).isEqualTo(1L));
            // 每个数据块的三个副本节点互不相同
            assertThat(nodes).doesNotHaveDuplicates();
        }
    }

    /** 机架不足时，额外副本落到同一机架的不同可用域，同一域内不重复。 */
    @Test
    void fallsBackToDifferentZonesWhenRacksInsufficient() {
        Topology topology = TestTopologies.grid(2, 2, 2);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(4, 2, 0.5));

        PlacementPlan plan = engine.place(Block.of("b1", 10));
        List<String> nodes = plan.nodesOf("b1");

        Map<String, Long> perRack = countByRack(topology, nodes);
        assertThat(perRack.values()).allSatisfy(count -> assertThat(count).isEqualTo(2L));

        // 同一机架内两个副本位于不同可用域
        Map<String, List<String>> racks = new HashMap<>();
        for (String nodeId : nodes) {
            Node node = topology.node(nodeId);
            racks.computeIfAbsent(node.rack(), k -> new java.util.ArrayList<>()).add(node.zone());
        }
        racks.values().forEach(zones -> assertThat(zones).doesNotHaveDuplicates());
        assertThat(nodes).doesNotHaveDuplicates();
    }

    /** 三副本、两机架时：一个机架 2 副本（不同域），另一个机架 1 副本。 */
    @Test
    void distributesAcrossRacksAndZonesWithThreeReplicasTwoRacks() {
        Topology topology = TestTopologies.grid(2, 2, 2);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 1, 0.5));

        PlacementPlan plan = engine.place(Block.of("b1", 10));
        Map<String, Long> perRack = countByRack(topology, plan.nodesOf("b1"));

        assertThat(perRack.values()).containsExactlyInAnyOrder(2L, 1L);
    }

    /** 域内节点选择倾向负载较轻者，多块放置后总体负载分布在各机架/域之间。 */
    @Test
    void spreadsLoadAcrossRacksOverManyBlocks() {
        Topology topology = TestTopologies.grid(3, 2, 2);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 2, 0.2));

        PlacementPlan plan = engine.placeAll(TestTopologies.blocks("b", 60, 10));
        PlacementStats stats = engine.stats(plan);

        assertThat(stats.rackDistribution().values()).containsOnly(60L);
        stats.rackDistribution().values().forEach(v -> assertThat(v).isEqualTo(60L));
        // 60 块 * 每机架 1 副本 = 60，分散到 4 个节点
        stats.nodeLoads().values().forEach(load -> {
            assertThat(load).isBetween(100L, 200L);
        });
        assertThat(stats.balanceDeviationRatio()).isLessThanOrEqualTo(0.2);
    }

    private Map<String, Long> countByRack(Topology topology, List<String> nodeIds) {
        Map<String, Long> perRack = new TreeMap<>();
        for (String nodeId : nodeIds) {
            perRack.merge(topology.node(nodeId).rack(), 1L, Long::sum);
        }
        return perRack;
    }
}
