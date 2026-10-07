package com.example.gsb.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.offset;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class PlacementStatsTest {

    @Test
    void reportsRackAndZoneDistribution() {
        Topology topology = TestTopologies.grid(3, 2, 1); // 6 节点
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 2, 0.5));
        PlacementPlan plan = engine.placeAll(TestTopologies.blocks("b", 30, 10));

        PlacementStats stats = engine.stats(plan);

        // 每块 3 副本、每机架 1 副本：30 块 -> 每机架 30 副本
        assertThat(stats.rackDistribution())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "rack1", 30L, "rack2", 30L, "rack3", 30L));
        // 6 个可用域合计 90 副本
        assertThat(stats.zoneDistribution()).hasSize(6);
        assertThat(stats.zoneDistribution().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(90L);
        assertThat(stats.zoneDistribution().keySet())
                .allSatisfy(key -> assertThat(key).matches("rack[123]/zone[12]"));
    }

    @Test
    void reportsNodeLoadsAndZeroDeviationForBalancedPlan() {
        Topology topology = TestTopologies.grid(3, 2, 1);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 2, 0.05));
        PlacementPlan plan = engine.placeAll(TestTopologies.blocks("b", 60, 10));

        PlacementStats stats = engine.stats(plan);

        // 60 块 * 3 副本 * 10 = 1800，6 节点均衡 -> 每节点 300
        assertThat(stats.nodeLoads().values()).containsOnly(300L);
        assertThat(stats.balanceDeviationRatio()).isEqualTo(0.0, offset(1e-9));
        assertThat(stats.migratedBytes()).isZero();
    }

    @Test
    void computesDeviationAsMaxMinOverAvg() {
        double deviation = PlacementStats.balanceDeviation(Map.of(
                "n1", 100L, "n2", 60L, "n3", 20L));
        // avg = 60, (100-20)/60
        assertThat(deviation).isEqualTo(80.0 / 60.0, offset(1e-9));
        assertThat(PlacementStats.balanceDeviation(Map.of())).isZero();
        assertThat(PlacementStats.balanceDeviation(Map.of("n1", 0L, "n2", 0L))).isZero();
    }

    @Test
    void reportsMigrationBytesAfterRebalance() {
        Topology topology = TestTopologies.grid(3, 2, 1);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 1, 0.15));
        PlacementPlan initial = engine.placeAll(TestTopologies.blocks("b", 24, 10));
        assertThat(engine.stats(initial).migratedBytes()).isZero();

        topology.addNode(new Node("n7", "rack1", "zone2"));
        PlacementPlan rebalanced = engine.rebalance(initial);
        PlacementStats stats = engine.stats(rebalanced);

        assertThat(stats.migratedBytes()).isEqualTo(rebalanced.migratedBytes());
        assertThat(stats.migratedBytes()).isPositive();
        // 迁移量 = 迁移条数 * 块大小
        assertThat(stats.migratedBytes())
                .isEqualTo(rebalanced.migrations().size() * 10L);
    }

    @Test
    void statsReflectMigrationsList() {
        Topology topology = TestTopologies.grid(3, 2, 1);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 1, 0.15));
        PlacementPlan initial = engine.placeAll(TestTopologies.blocks("b", 24, 10));

        topology.addNode(new Node("n7", "rack1", "zone2"));
        PlacementPlan rebalanced = engine.rebalance(initial);

        List<Migration> migrations = rebalanced.migrations();
        assertThat(migrations).isNotEmpty();
        for (Migration migration : migrations) {
            // 迁移后源节点不再持有该块副本，目标节点持有
            assertThat(rebalanced.nodesOf(migration.blockId()))
                    .contains(migration.toNode())
                    .doesNotContain(migration.fromNode());
            assertThat(migration.bytes()).isEqualTo(10L);
        }
    }
}
