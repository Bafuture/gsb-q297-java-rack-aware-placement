package com.example.gsb.placement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class NodeChangeRebalanceTest {

    /** 新增节点：仅通过必要的迁移把数据摊到新节点，并给出迁移量统计。 */
    @Test
    void rebalancesAfterAddingNodeWithMinimalMigration() {
        Topology topology = TestTopologies.grid(3, 2, 1); // 6 节点
        double ratio = 0.15;
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 1, ratio));
        PlacementPlan initial = engine.placeAll(TestTopologies.blocks("b", 24, 10));
        long totalBytes = 720L;
        assertThat(engine.stats(initial).migratedBytes()).isZero();

        // 新增 n7：rack1 的 zone2 中增加第二个节点
        topology.addNode(new Node("n7", "rack1", "zone2"));
        PlacementPlan rebalanced = engine.rebalance(initial);
        PlacementStats stats = engine.stats(rebalanced);

        assertThat(stats.balanceDeviationRatio()).isLessThanOrEqualTo(ratio);
        assertThat(rebalanced.migrations()).isNotEmpty();
        rebalanced.migrations().forEach(m -> assertThat(m.bytes()).isEqualTo(10L));
        assertThat(rebalanced.migrations().stream().map(Migration::toNode).distinct())
                .contains("n7");
        // 迁移量即统计值，且为满足均衡所需的最小量级（新节点最终承载量即迁入量下界）
        assertThat(rebalanced.migratedBytes()).isEqualTo(stats.migratedBytes());
        long newNodeLoad = stats.nodeLoads().get("n7");
        assertThat(rebalanced.migratedBytes()).isGreaterThanOrEqualTo(newNodeLoad);
        // 迁移量远小于全量重排（72 个副本 * 10 = 720）
        assertThat(rebalanced.migratedBytes()).isLessThan(300L);

        // 数据总量守恒
        assertThat(stats.nodeLoads().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(totalBytes);
        // 所有数据块仍满足机架约束：每个机架至多 2 副本（=可用域数）
        for (List<String> nodes : rebalanced.blockToNodes().values()) {
            assertThat(nodes).hasSize(3).doesNotHaveDuplicates();
            assertThat(engine.satisfiesRackRules(nodes)).isTrue();
        }
    }

    /** 删除节点：该节点上的副本全部迁移到合法位置，之后继续满足均衡。 */
    @Test
    void relocatesReplicasAfterRemovingNode() {
        Topology topology = TestTopologies.grid(3, 2, 1); // 6 节点
        double ratio = 0.2;
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 1, ratio));
        PlacementPlan initial = engine.placeAll(TestTopologies.blocks("b", 24, 10));

        topology.removeNode("n2");
        PlacementPlan rebalanced = engine.rebalance(initial);
        PlacementStats stats = engine.stats(rebalanced);

        // 没有任何副本残留在已删除节点上
        for (List<String> nodes : rebalanced.blockToNodes().values()) {
            assertThat(nodes).hasSize(3).doesNotHaveDuplicates().doesNotContain("n2");
            assertThat(engine.satisfiesRackRules(nodes)).isTrue();
        }
        // n2 原先承载 12 个副本（24 块均匀分到 6 节点），至少迁移 120
        assertThat(rebalanced.migratedBytes()).isGreaterThanOrEqualTo(120L);
        assertThat(rebalanced.migratedBytes()).isEqualTo(stats.migratedBytes());
        assertThat(stats.balanceDeviationRatio()).isLessThanOrEqualTo(ratio);
        assertThat(stats.nodeLoads().keySet()).doesNotContain("n2");
        assertThat(stats.nodeLoads().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(720L);
    }

    /** 节点未变化时重放不应产生迁移。 */
    @Test
    void noMigrationWhenTopologyUnchangedAndBalanced() {
        Topology topology = TestTopologies.grid(3, 2, 2);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 2, 0.2));
        PlacementPlan initial = engine.placeAll(TestTopologies.blocks("b", 48, 10));

        PlacementPlan replayed = engine.rebalance(initial);

        assertThat(replayed.migrations()).isEmpty();
        assertThat(replayed.migratedBytes()).isZero();
        assertThat(replayed.blockToNodes()).isEqualTo(initial.blockToNodes());
    }
}
