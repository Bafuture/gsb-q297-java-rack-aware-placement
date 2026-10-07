package com.example.gsb.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class BalanceTest {

    /** 批量放置后各节点承载量偏差不超过配置比例。 */
    @Test
    void balancesNodeLoadsWithinConfiguredRatio() {
        Topology topology = TestTopologies.grid(3, 2, 2);
        double ratio = 0.2;
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 2, ratio));

        PlacementPlan plan = engine.placeAll(TestTopologies.blocks("b", 60, 10));
        PlacementStats stats = engine.stats(plan);

        assertThat(stats.balanceDeviationRatio()).isLessThanOrEqualTo(ratio);
        long max = max(stats);
        long min = min(stats);
        double expected = (double) (max - min)
                / stats.nodeLoads().values().stream().mapToLong(Long::longValue).average().orElseThrow();
        assertThat(stats.balanceDeviationRatio()).isEqualTo(expected, org.assertj.core.data.Offset.offset(1e-12));
    }

    /** 混合块大小也能在比例内均衡。 */
    @Test
    void balancesWithMixedBlockSizes() {
        Topology topology = TestTopologies.grid(3, 2, 1);
        double ratio = 0.25;
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 2, ratio));

        List<Block> blocks = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            blocks.add(Block.of("b" + i, (i % 3 + 1) * 10));
        }
        PlacementPlan plan = engine.placeAll(blocks);

        assertThat(engine.stats(plan).balanceDeviationRatio()).isLessThanOrEqualTo(ratio);
    }

    /** 无法达到完美均衡（ratio=0）时抛出明确的约束冲突异常。 */
    @Test
    void throwsWhenBalanceImpossible() {
        Topology topology = TestTopologies.grid(3, 1, 1);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(2, 1, 0.0));

        assertThatThrownBy(() -> engine.placeAll(List.of(
                Block.of("b1", 10), Block.of("b2", 20), Block.of("b3", 40))))
                .isInstanceOf(PlacementException.class)
                .hasMessageContaining("均衡约束冲突");
    }

    /** 数据总量守恒：均衡迁移不改变每个节点集合上的总数据量。 */
    @Test
    void totalDataIsConserved() {
        Topology topology = TestTopologies.grid(3, 2, 2);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 2, 0.1));
        List<Block> blocks = TestTopologies.blocks("b", 45, 10);
        PlacementPlan plan = engine.placeAll(blocks);

        long total = engine.stats(plan).nodeLoads().values().stream().mapToLong(Long::longValue).sum();
        assertThat(total).isEqualTo(45L * 10 * 3);
    }

    private static long max(PlacementStats stats) {
        return stats.nodeLoads().values().stream().mapToLong(Long::longValue).max().orElseThrow();
    }

    private static long min(PlacementStats stats) {
        return stats.nodeLoads().values().stream().mapToLong(Long::longValue).min().orElseThrow();
    }
}
