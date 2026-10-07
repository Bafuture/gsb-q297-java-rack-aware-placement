package com.example.gsb.placement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 机架故障模拟：失效后剩余副本必须满足最小可用数。 */
class RackFailureSurvivalTest {

    @Test
    void everySingleRackFailureKeepsMinimumAvailableReplicas() {
        ReplicaPlacementManager manager =
                TestClusters.cluster(PlacementPolicy.of(3, 2, 0.2), 3, 2, 2);
        for (int i = 0; i < 50; i++) {
            manager.place("group-" + i);
        }

        for (String rack : List.of("rack-1", "rack-2", "rack-3")) {
            RackFailureResult result = manager.simulateRackFailure(rack);
            assertThat(result.violatingGroups()).as("failure of %s", rack).isEmpty();
            assertThat(result.survivingReplicas().values())
                    .allMatch(count -> count >= 2);
            assertThat(result.satisfied()).isTrue();
        }
    }

    @Test
    void randomRackFailuresKeepMinimumAvailableReplicas() {
        ReplicaPlacementManager manager =
                TestClusters.cluster(PlacementPolicy.of(3, 2, 0.2), 3, 2, 2);
        for (int i = 0; i < 50; i++) {
            manager.place("group-" + i);
        }

        Random random = new Random(42L);
        Set<String> failedRacks = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            RackFailureResult result = manager.simulateRandomRackFailure(random);
            failedRacks.add(result.failedRackId());
            assertThat(result.satisfied())
                    .as("random failure of %s", result.failedRackId())
                    .isTrue();
        }
        assertThat(failedRacks).containsExactlyInAnyOrder("rack-1", "rack-2", "rack-3");
    }

    @Test
    void higherReplicationFactorLosesAtMostOneReplicaPerRackFailure() {
        // 4 机架 × 1 域 × 1 节点，副本数 4，故障后至少保留 3
        ReplicaPlacementManager manager =
                TestClusters.cluster(PlacementPolicy.of(4, 3, 0.2), 4, 1, 1);
        manager.place("group-1");

        for (String rack : List.of("rack-1", "rack-2", "rack-3", "rack-4")) {
            RackFailureResult result = manager.simulateRackFailure(rack);
            assertThat(result.survivingReplicas().get("group-1")).isEqualTo(3);
            assertThat(result.satisfied()).isTrue();
        }
    }

    @Test
    void failureSimulationDoesNotMutatePlacements() {
        ReplicaPlacementManager manager =
                TestClusters.cluster(PlacementPolicy.of(3, 2, 0.2), 3, 2, 2);
        manager.place("group-1");
        List<String> before = manager.placementOf("group-1").nodeIds();

        manager.simulateRackFailure("rack-1");
        manager.simulateRandomRackFailure(new Random(1L));

        assertThat(manager.placementOf("group-1").nodeIds()).isEqualTo(before);
    }
}
