package com.example.gsb.placement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 节点增删后的最小迁移重放置与迁移量统计。 */
class TopologyChangeRebalanceTest {

    @Test
    void addingNodeMigratesOnlyNecessaryData() {
        PlacementPolicy policy = new PlacementPolicy(3, 2, 0.35, 128L);
        ReplicaPlacementManager manager = TestClusters.cluster(policy, 3, 2, 2);
        for (int i = 0; i < 130; i++) {
            manager.place("group-" + i);
        }
        Map<String, List<String>> before = snapshot(manager);
        int totalReplicas = 130 * 3;

        MigrationReport report = manager.addNode(new Node("n-new", "rack-1", "zone-1-1"));

        assertThat(report.migratedReplicas()).isGreaterThan(0);
        assertThat(report.migratedReplicas()).isLessThan((int) (totalReplicas * 0.2));
        assertThat(report.migratedDataVolume())
                .isEqualTo(report.migratedReplicas() * 128L);
        assertThat(countChangedSlots(before, manager)).isEqualTo(report.migratedReplicas());
        assertThat(manager.statistics().nodeLoad().get("n-new")).isGreaterThan(0);
        assertThat(manager.statistics().balanceDeviation())
                .isLessThanOrEqualTo(policy.maxBalanceDeviation());
    }

    @Test
    void removingNodeRelocatesItsReplicasAndRestoresBalance() {
        PlacementPolicy policy = PlacementPolicy.of(3, 2, 0.25);
        ReplicaPlacementManager manager = TestClusters.cluster(policy, 3, 2, 3);
        for (int i = 0; i < 130; i++) {
            manager.place("group-" + i);
        }
        int loadBefore = manager.statistics().nodeLoad().get("n-r1-z1-1");
        Map<String, List<String>> before = snapshot(manager);

        MigrationReport report = manager.removeNode("n-r1-z1-1");

        assertThat(report.migratedReplicas()).isGreaterThanOrEqualTo(loadBefore);
        assertThat(report.migratedReplicas()).isLessThanOrEqualTo(loadBefore + 20);
        assertThat(manager.placements())
                .allMatch(placement -> !placement.nodeIds().contains("n-r1-z1-1"));
        Map<String, Node> nodes = TestClusters.nodesById(manager);
        assertThat(manager.placements()).allSatisfy(placement -> {
            assertThat(placement.nodeIds()).hasSize(3).doesNotHaveDuplicates();
            assertThat(placement.nodeIds().stream()
                    .map(id -> nodes.get(id).rackId())
                    .distinct())
                    .hasSize(3);
        });
        // 其余副本位置保持不变，只迁移了必要的副本
        int moved = countChangedSlots(before, manager);
        assertThat(moved).isEqualTo(report.migratedReplicas());
        assertThat(manager.statistics().balanceDeviation())
                .isLessThanOrEqualTo(policy.maxBalanceDeviation());
        assertThat(manager.statistics().totalMigratedReplicas())
                .isEqualTo(report.migratedReplicas());
    }

    @Test
    void explicitRebalanceIsANoOpWhenAlreadyBalanced() {
        PlacementPolicy policy = PlacementPolicy.of(3, 2, 0.2);
        ReplicaPlacementManager manager = TestClusters.cluster(policy, 3, 2, 2);
        for (int i = 0; i < 60; i++) {
            manager.place("group-" + i);
        }
        MigrationReport report = manager.rebalance();
        assertThat(report.isEmpty()).isTrue();
        assertThat(report.migratedReplicas()).isZero();
    }

    private static Map<String, List<String>> snapshot(ReplicaPlacementManager manager) {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (Placement placement : manager.placements()) {
            snapshot.put(placement.groupId(), new java.util.ArrayList<>(placement.nodeIds()));
        }
        return snapshot;
    }

    private static int countChangedSlots(Map<String, List<String>> before,
                                        ReplicaPlacementManager manager) {
        int changed = 0;
        for (Placement placement : manager.placements()) {
            List<String> old = before.get(placement.groupId());
            List<String> current = placement.nodeIds();
            assertThat(old).hasSameSizeAs(current);
            for (int i = 0; i < old.size(); i++) {
                if (!old.get(i).equals(current.get(i))) {
                    changed++;
                }
            }
        }
        return changed;
    }
}
