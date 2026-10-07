package com.example.gsb.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class BalanceEnforcementTest {

    /** 各节点承载量差异 (max-min)/avg 不超过配置比例。 */
    @Test
    void keepsLoadDeviationWithinConfiguredRatio() {
        Topology topology = TestTopologies.grid(3, 2, 2); // 12 节点
        double ratio = 0.2;
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 2, ratio));

        PlacementPlan plan = engine.placeAll(TestTopologies.blocks("b", 60, 10));
        PlacementStats stats = engine.stats(plan);

        assertThat(stats.balanceDeviationRatio()).isLessThanOrEqualTo(ratio);
        long max = stats.nodeLoads().values().stream().mapToLong(Long::longValue).max().orElseThrow();
        long min = stats.nodeLoads().values().stream().mapToLong(Long::longValue).min().orElseThrow();
        double avg = stats.nodeLoads().values().stream().mapToLong(Long::longValue).average().orElseThrow();
        assertThat((max - min) / avg).isLessThanOrEqualTo(ratio);
    }

    /** 不同块大小混合放置仍满足均衡比例。 */
    @Test
    void balancesMixedBlockSizes() {
        Topology topology = TestTopologies.grid(3, 2, 2);
        double ratio = 0.3;
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 2, ratio));

        List<Block> blocks = new java.util.ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            blocks.add(Block.of("b" + i, i % 4 == 0 ? 40 : 10));
        }
        PlacementPlan plan = engine.placeAll(blocks);

        assertThat(engine.stats(plan).balanceDeviationRatio()).isLessThanOrEqualTo(ratio);
    }

    /** 均衡约束在物理上无法满足时给出明确报错。 */
    @Test
    void throwsWhenBalanceRatioUnsatisfiable() {
        Topology topology = TestTopologies.grid(3, 1, 1); // 3 节点、3 域
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(2, 1, 0.0));

        // 总数据量 140 无法被 3 个节点均分，ratio=0 不可能满足
        assertThatThrownBy(() -> engine.placeAll(List.of(
                Block.of("b1", 10), Block.of("b2", 20), Block.of("b3", 40))))
                .isInstanceOf(PlacementException.class)
                .hasMessageContaining("均衡约束冲突");
    }

    /** 均衡迁移不破坏机架/可用域硬约束。 */
    @Test
    void balanceMigrationsRespectRackRules() {
        Topology topology = TestTopologies.grid(3, 2, 1);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 2, 0.1));

        PlacementPlan plan = engine.placeAll(TestTopologies.blocks("b", 36, 10));

        for (List<String> nodes : plan.blockToNodes().values()) {
            assertThat(engine.satisfiesRackRules(nodes)).isTrue();
        }
        assertThat(engine.stats(plan).balanceDeviationRatio()).isLessThanOrEqualTo(0.1);
    }
}
