package com.example.gsb.placement;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 测试用集群构建工具。 */
final class TestClusters {

    private TestClusters() {
    }

    /** 构建 racks × zonesPerRack × nodesPerZone 的规则拓扑。 */
    static ReplicaPlacementManager cluster(PlacementPolicy policy,
                                           int racks, int zonesPerRack, int nodesPerZone) {
        ReplicaPlacementManager manager = new ReplicaPlacementManager(policy);
        for (int rack = 1; rack <= racks; rack++) {
            for (int zone = 1; zone <= zonesPerRack; zone++) {
                for (int n = 1; n <= nodesPerZone; n++) {
                    manager.addNode(new Node(
                            "n-r" + rack + "-z" + zone + "-" + n,
                            "rack-" + rack,
                            "zone-" + rack + "-" + zone));
                }
            }
        }
        return manager;
    }

    static Map<String, Node> nodesById(ReplicaPlacementManager manager) {
        return manager.nodes().stream()
                .collect(Collectors.toMap(Node::id, Function.identity()));
    }
}
