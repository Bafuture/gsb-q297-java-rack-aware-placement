package com.example.gsb.placement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class RackFailureSurvivalTest {

    /** 随机机架失效（多个随机种子）后，每个数据块存活副本数 >= minAvailable。 */
    @Test
    void survivesRandomRackFailureAcrossSeeds() {
        for (long seed = 0; seed < 20; seed++) {
            Topology topology = TestTopologies.grid(3, 2, 2);
            RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                    topology, PlacementConfig.of(4, 2, 0.5));
            PlacementPlan plan = engine.placeAll(TestTopologies.blocks("b", 40, 10));

            RackFailureSimulator simulator = new RackFailureSimulator(seed);
            String failedRack = simulator.failRandomRack(topology);

            assertThat(topology.nodesByRack()).doesNotContainKey(failedRack);
            assertThat(simulator.minSurvivingReplicas(plan, topology)).isGreaterThanOrEqualTo(2);
            assertThatCode(() -> simulator.verifySurvives(plan, topology, 2))
                    .doesNotThrowAnyException();
        }
    }

    /** 指定机架失效时，损失副本数不超过单机架最大副本数。 */
    @Test
    void losesAtMostOneRackShareOfReplicas() {
        Topology topology = TestTopologies.grid(3, 2, 2);
        RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                topology, PlacementConfig.of(3, 2, 0.5));
        PlacementPlan plan = engine.placeAll(TestTopologies.blocks("b", 30, 10));

        RackFailureSimulator simulator = new RackFailureSimulator(42L);
        simulator.failRack(topology, "rack2");

        // 每个机架恰好 1 副本，因此失效后每个数据块恰好剩 2 副本
        simulator.survivingReplicas(plan, topology)
                .values()
                .forEach(surviving -> assertThat(surviving).isEqualTo(2));
    }

    /** 两机架三副本场景：任一机架失效后至少保留 1 个副本。 */
    @Test
    void survivesWithTwoRacks() {
        for (long seed = 0; seed < 10; seed++) {
            Topology topology = TestTopologies.grid(2, 2, 2);
            RackAwarePlacementEngine engine = new RackAwarePlacementEngine(
                    topology, PlacementConfig.of(3, 1, 0.5));
            PlacementPlan plan = engine.placeAll(TestTopologies.blocks("b", 20, 10));

            RackFailureSimulator simulator = new RackFailureSimulator(seed);
            simulator.failRandomRack(topology);

            assertThat(simulator.minSurvivingReplicas(plan, topology)).isGreaterThanOrEqualTo(1);
        }
    }

    /** verifySurvives 在不满足时抛出明确异常。 */
    @Test
    void verifySurvivesThrowsWhenBelowMinimum() {
        Topology topology = TestTopologies.grid(2, 1, 1);
        // 手工构造一个违反约束的方案：两个副本都在 rack1
        PlacementPlan plan = PlacementPlan.builder()
                .put("b1", 10, List.of("n1"))
                .build();

        RackFailureSimulator simulator = new RackFailureSimulator(1L);
        simulator.failRack(topology, "rack1");

        assertThatThrownBy(() -> simulator.verifySurvives(plan, topology, 1))
                .isInstanceOf(PlacementException.class)
                .hasMessageContaining("低于最小可用数");
    }

    /** 空拓扑上模拟故障给出明确报错。 */
    @Test
    void failsClearlyOnEmptyTopology() {
        RackFailureSimulator simulator = new RackFailureSimulator(0L);
        assertThatThrownBy(() -> simulator.failRandomRack(new Topology()))
                .isInstanceOf(PlacementException.class)
                .hasMessageContaining("没有任何机架");
    }
}
