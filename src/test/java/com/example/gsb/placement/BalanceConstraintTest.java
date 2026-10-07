package com.example.gsb.placement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 数据均衡：各节点承载数据量差异不超过配置比例。 */
class BalanceConstraintTest {

    @Test
    void keepsNodeLoadDeviationWithinConfiguredRatio() {
        PlacementPolicy policy = PlacementPolicy.of(3, 2, 0.10);
        ReplicaPlacementManager manager = TestClusters.cluster(policy, 3, 2, 2);
        for (int i = 0; i < 200; i++) {
            manager.place("group-" + i);
        }

        PlacementStatistics stats = manager.statistics();
        assertThat(stats.balanceDeviation())
                .isLessThanOrEqualTo(policy.maxBalanceDeviation());

        int max = stats.nodeLoad().values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        int min = stats.nodeLoad().values().stream().mapToInt(Integer::intValue).min().orElseThrow();
        assertThat(max - min)
                .isLessThanOrEqualTo((int) (policy.maxBalanceDeviation() * stats.averageLoad()));
    }

    @Test
    void balanceHoldsAcrossManyPlacementsAndRemoval() {
        PlacementPolicy policy = PlacementPolicy.of(3, 2, 0.25);
        ReplicaPlacementManager manager = TestClusters.cluster(policy, 3, 2, 3);
        for (int i = 0; i < 130; i++) {
            manager.place("group-" + i);
        }
        manager.removeNode("n-r1-z1-1");

        assertThat(manager.statistics().balanceDeviation())
                .isLessThanOrEqualTo(policy.maxBalanceDeviation());
    }

    @Test
    void deviationIsZeroForEmptyCluster() {
        ReplicaPlacementManager manager =
                TestClusters.cluster(PlacementPolicy.of(3, 2, 0.2), 3, 2, 2);
        assertThat(manager.statistics().balanceDeviation()).isZero();
        assertThat(manager.statistics().averageLoad()).isZero();
    }
}
