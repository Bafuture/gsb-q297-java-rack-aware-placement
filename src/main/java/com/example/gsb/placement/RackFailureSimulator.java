package com.example.gsb.placement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * 机架故障模拟器：随机选取一个机架使其失效，并评估各数据块的存活副本数。
 */
public final class RackFailureSimulator {

    private final Random random;

    public RackFailureSimulator(long seed) {
        this.random = new Random(seed);
    }

    /**
     * 随机让一个机架失效：从拓扑中移除该机架上的全部节点，返回失效的机架 id。
     */
    public String failRandomRack(Topology topology) {
        List<String> racks = new ArrayList<>(topology.nodesByRack().keySet());
        if (racks.isEmpty()) {
            throw new PlacementException("集群中没有任何机架，无法模拟机架故障");
        }
        String victim = racks.get(random.nextInt(racks.size()));
        failRack(topology, victim);
        return victim;
    }

    /** 让指定机架失效：移除其全部节点。 */
    public void failRack(Topology topology, String rack) {
        List<Node> nodes = topology.nodesByRack().get(rack);
        if (nodes == null) {
            throw new IllegalArgumentException("unknown rack: " + rack);
        }
        for (Node node : nodes) {
            topology.removeNode(node.id());
        }
    }

    /** 计算机架失效后每个数据块的存活副本数。 */
    public Map<String, Integer> survivingReplicas(PlacementPlan plan, Topology topologyAfterFailure) {
        Map<String, Integer> surviving = new TreeMap<>();
        plan.blockToNodes().forEach((blockId, nodeIds) -> {
            int count = 0;
            for (String nodeId : nodeIds) {
                if (topologyAfterFailure.contains(nodeId)) {
                    count++;
                }
            }
            surviving.put(blockId, count);
        });
        return surviving;
    }

    /** 所有数据块在机架失效后的最小存活副本数。 */
    public int minSurvivingReplicas(PlacementPlan plan, Topology topologyAfterFailure) {
        return survivingReplicas(plan, topologyAfterFailure).values().stream()
                .mapToInt(Integer::intValue)
                .min()
                .orElse(0);
    }

    /**
     * 校验机架失效后每个数据块的存活副本数均不少于 minAvailable，否则抛出约束异常。
     */
    public void verifySurvives(PlacementPlan plan, Topology topologyAfterFailure, int minAvailable) {
        survivingReplicas(plan, topologyAfterFailure).forEach((blockId, count) -> {
            if (count < minAvailable) {
                throw new PlacementException("机架故障后数据块 " + blockId + " 仅剩 " + count
                        + " 个副本，低于最小可用数 " + minAvailable);
            }
        });
    }
}
