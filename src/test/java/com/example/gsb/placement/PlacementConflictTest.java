package com.example.gsb.placement;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlacementConflictTest {

    /** 副本数超过可用机架/可用域组合上限。 */
    @Test
    void rejectsMoreReplicasThanRackZoneCombinations() {
        Topology topology = TestTopologies.grid(3, 1, 2); // 6 节点但只有 3 个可用域
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(5, 1, 1.0));

        assertThatThrownBy(() -> engine.place(Block.of("b1", 10)))
                .isInstanceOf(PlacementException.class)
                .hasMessageContaining("可用机架与可用域的组合上限");
    }

    /** 副本数超过节点总数。 */
    @Test
    void rejectsMoreReplicasThanNodes() {
        Topology topology = TestTopologies.grid(2, 1, 3); // 6 节点、2 域
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(7, 1, 1.0));

        assertThatThrownBy(() -> engine.place(Block.of("b1", 10)))
                .isInstanceOf(PlacementException.class)
                .hasMessageContaining("超过可用节点数");
    }

    /** 拓扑无法满足机架故障后的最小可用数（两机架、3 副本要保 2 份，必然有一机架放 2 份）。 */
    @Test
    void rejectsInfeasibleMinAvailable() {
        Topology topology = TestTopologies.grid(2, 2, 2);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 2, 1.0));

        assertThatThrownBy(() -> engine.place(Block.of("b1", 10)))
                .isInstanceOf(PlacementException.class)
                .hasMessageContaining("minAvailable=2");
    }

    /** 单机架集群永远无法在机架故障后保留副本。 */
    @Test
    void rejectsSingleRackWithMinAvailableRequirement() {
        Topology topology = TestTopologies.grid(1, 3, 1);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(2, 1, 1.0));

        assertThatThrownBy(() -> engine.place(Block.of("b1", 10)))
                .isInstanceOf(PlacementException.class);
    }

    /** minAvailable == replicationFactor 在物理上不可能。 */
    @Test
    void rejectsMinAvailableEqualToReplicationFactor() {
        Topology topology = TestTopologies.grid(4, 2, 2);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 3, 1.0));

        assertThatThrownBy(() -> engine.place(Block.of("b1", 10)))
                .isInstanceOf(PlacementException.class)
                .hasMessageContaining("minAvailable=3");
    }

    /** 删除节点后无合法承接节点时给出明确报错。 */
    @Test
    void rejectsRebalanceWhenNoNodeCanTakeOver() {
        Topology topology = TestTopologies.grid(2, 1, 2);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(2, 1, 1.0));
        PlacementPlan initial = engine.place(Block.of("b1", 10));

        // 每个机架仅剩 1 节点时，两副本分别在两机架；再删除一个机架上的节点，
        // 该副本无法迁入另一个机架（违反 minAvailable=1 且同域冲突）
        topology.removeNode("n3");
        topology.removeNode("n4");
        topology.removeNode("n2");

        assertThatThrownBy(() -> engine.rebalance(initial))
                .isInstanceOf(PlacementException.class)
                .hasMessageContaining("找不到满足机架/可用域约束的承接节点");
    }

    /** 空集群放置给出明确报错。 */
    @Test
    void rejectsPlacementOnEmptyCluster() {
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                new Topology(), PlacementConfig.of(1, 1, 1.0));
        assertThatThrownBy(() -> engine.place(Block.of("b1", 10)))
                .isInstanceOf(PlacementException.class)
                .hasMessageContaining("没有可用节点");
    }
}
