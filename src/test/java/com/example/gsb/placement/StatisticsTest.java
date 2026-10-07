package com.example.gsb.placement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 统计：机架分布、可用域分布、均衡偏差、迁移数据量。 */
class StatisticsTest {

    @Test
    void reportsRackZoneAndNodeDistributions() {
        ReplicaPlacementManager manager =
                TestClusters.cluster(PlacementPolicy.of(3, 2, 0.2), 3, 2, 2);
        for (int i = 0; i < 60; i++) {
            manager.place("group-" + i);
        }

        PlacementStatistics stats = manager.statistics();
        assertThat(stats.rackDistribution())
                .containsEntry("rack-1", 60)
                .containsEntry("rack-2", 60)
                .containsEntry("rack-3", 60);
        assertThat(stats.zoneDistribution().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(180);
        assertThat(stats.zoneDistribution()).allSatisfy((zone, count) ->
                assertThat(count).isPositive());
        assertThat(stats.nodeLoad().values().stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(180);
        assertThat(stats.averageLoad()).isEqualTo(15.0);
        assertThat(stats.balanceDeviation()).isBetween(0.0, 0.2);
        assertThat(stats.totalMigratedReplicas()).isZero();
        assertThat(stats.totalMigratedDataVolume()).isZero();
    }

    @Test
    void accumulatesMigrationVolumeAcrossTopologyChanges() {
        PlacementPolicy policy = new PlacementPolicy(3, 2, 0.35, 256L);
        ReplicaPlacementManager manager = TestClusters.cluster(policy, 3, 2, 3);
        for (int i = 0; i < 100; i++) {
            manager.place("group-" + i);
        }

        MigrationReport removeReport = manager.removeNode("n-r1-z1-1");
        MigrationReport addReport = manager.addNode(new Node("n-new", "rack-1", "zone-1-1"));

        assertThat(removeReport.migratedReplicas()).isGreaterThan(0);
        assertThat(addReport.migratedReplicas()).isGreaterThan(0);
        assertThat(addReport.migrations()).isNotEmpty();
        assertThat(addReport.migrations().get(0).fromNodeId()).isNotEqualTo(
                addReport.migrations().get(0).toNodeId());

        long total = removeReport.migratedReplicas() + addReport.migratedReplicas();
        PlacementStatistics stats = manager.statistics();
        assertThat(stats.totalMigratedReplicas()).isEqualTo(total);
        assertThat(stats.totalMigratedDataVolume()).isEqualTo(total * 256L);
    }
}
