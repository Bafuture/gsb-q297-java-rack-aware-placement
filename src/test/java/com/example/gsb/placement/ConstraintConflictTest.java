package com.example.gsb.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 放置约束冲突的明确报错。 */
class ConstraintConflictTest {

    @Test
    void rejectsReplicationFactorExceedingNodeCount() {
        ReplicaPlacementManager manager =
                TestClusters.cluster(PlacementPolicy.of(4, 1, 0.2), 3, 1, 1);

        assertThatThrownBy(() -> manager.place("group-1"))
                .isInstanceOf(PlacementException.class)
                .hasMessageContaining("可用节点数");
    }

    @Test
    void rejectsReplicationFactorExceedingRackZoneCombinationLimit() {
        // 2 机架 × 2 可用域 = 4 个机架/域组合，但有 6 个节点；副本数 5 超过组合上限
        ReplicaPlacementManager manager =
                new ReplicaPlacementManager(PlacementPolicy.of(5, 1, 0.2));
        manager.addNode(new Node("a1", "rack-1", "zone-a"));
        manager.addNode(new Node("a2", "rack-1", "zone-a"));
        manager.addNode(new Node("b1", "rack-1", "zone-b"));
        manager.addNode(new Node("c1", "rack-2", "zone-c"));
        manager.addNode(new Node("c2", "rack-2", "zone-c"));
        manager.addNode(new Node("d1", "rack-2", "zone-d"));

        assertThatThrownBy(() -> manager.place("group-1"))
                .isInstanceOf(PlacementException.class)
                .hasMessageContaining("机架/可用域组合上限");
    }

    @Test
    void rejectsWhenRacksInsufficientForFailureSurvival() {
        // 副本数 3、故障后至少保留 2，意味着每机架最多 1 副本；2 个机架不够
        ReplicaPlacementManager manager =
                TestClusters.cluster(PlacementPolicy.of(3, 2, 0.2), 2, 2, 2);

        assertThatThrownBy(() -> manager.place("group-1"))
                .isInstanceOf(PlacementException.class)
                .hasMessageContaining("机架数量不足");
    }

    @Test
    void rejectsInvalidPolicyArguments() {
        assertThatThrownBy(() -> new PlacementPolicy(3, 3, 0.2, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlacementPolicy(0, 0, 0.2, 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlacementPolicy(3, 2, -0.1, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateGroupAndDuplicateNode() {
        ReplicaPlacementManager manager =
                TestClusters.cluster(PlacementPolicy.of(3, 2, 0.2), 3, 2, 2);
        manager.place("group-1");
        assertThatThrownBy(() -> manager.place("group-1"))
                .isInstanceOf(PlacementException.class)
                .hasMessageContaining("数据组已存在");
        assertThatThrownBy(() -> manager.addNode(new Node("n-r1-z1-1", "rack-9", "zone-9-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void removingNodeThatBreaksConstraintsFailsLoudlyAndRollsBack() {
        // 3 机架各 1 节点，删掉任一节点后无法再满足 3 副本
        ReplicaPlacementManager manager =
                TestClusters.cluster(PlacementPolicy.of(3, 2, 0.2), 3, 1, 1);
        manager.place("group-1");

        assertThatThrownBy(() -> manager.removeNode("n-r1-z1-1"))
                .isInstanceOf(PlacementException.class)
                .hasMessageContaining("可用节点数");

        // 拓扑与放置均回滚，未被部分修改
        assertThat(manager.node("n-r1-z1-1")).isNotNull();
        assertThat(manager.placementOf("group-1").nodeIds()).hasSize(3);
        assertThat(manager.statistics().nodeLoad()).containsKey("n-r1-z1-1");
    }
}
