package com.example.gsb.placement;

import java.util.ArrayList;
import java.util.List;

/** 测试用拓扑构建工具。 */
final class TestTopologies {

    private TestTopologies() {
    }

    /**
     * 构建 racks x zonesPerRack x nodesPerZone 的拓扑，
     * 节点 id 为 n1..nN，机架为 rack1..rackR，可用域为 zone1..zoneZ。
     */
    static Topology grid(int racks, int zonesPerRack, int nodesPerZone) {
        Topology topology = new Topology();
        int seq = 0;
        for (int r = 1; r <= racks; r++) {
            for (int z = 1; z <= zonesPerRack; z++) {
                for (int n = 0; n < nodesPerZone; n++) {
                    seq++;
                    topology.addNode(new Node("n" + seq, "rack" + r, "zone" + z));
                }
            }
        }
        return topology;
    }

    static List<Block> blocks(String prefix, int count, long size) {
        List<Block> blocks = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            blocks.add(Block.of(prefix + "-" + i, size));
        }
        return blocks;
    }
}
