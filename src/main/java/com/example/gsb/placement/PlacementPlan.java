package com.example.gsb.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一次放置/重放的结果：每个数据块的副本落在哪些节点上，以及本次产生的迁移。
 */
public final class PlacementPlan {

    private final Map<String, List<String>> blockToNodes;
    private final Map<String, Long> blockSizes;
    private final List<Migration> migrations;

    PlacementPlan(Map<String, List<String>> blockToNodes,
                  Map<String, Long> blockSizes,
                  List<Migration> migrations) {
        Map<String, List<String>> blocks = new LinkedHashMap<>();
        blockToNodes.forEach((k, v) -> blocks.put(k, List.copyOf(v)));
        this.blockToNodes = Collections.unmodifiableMap(blocks);
        this.blockSizes = Collections.unmodifiableMap(new LinkedHashMap<>(blockSizes));
        this.migrations = List.copyOf(migrations);
    }

    /** 数据块 id -> 副本所在节点 id 列表。 */
    public Map<String, List<String>> blockToNodes() {
        return blockToNodes;
    }

    public List<String> nodesOf(String blockId) {
        List<String> nodes = blockToNodes.get(blockId);
        if (nodes == null) {
            throw new IllegalArgumentException("unknown block id: " + blockId);
        }
        return nodes;
    }

    public long sizeOf(String blockId) {
        Long size = blockSizes.get(blockId);
        if (size == null) {
            throw new IllegalArgumentException("unknown block id: " + blockId);
        }
        return size;
    }

    /** 本次操作产生的迁移列表（初始放置时为空）。 */
    public List<Migration> migrations() {
        return migrations;
    }

    /** 本次操作迁移的总数据量。 */
    public long migratedBytes() {
        return migrations.stream().mapToLong(Migration::bytes).sum();
    }

    /** 每个节点当前承载的总数据量（含未承载任何数据的节点，由 topology 提供全集）。 */
    public Map<String, Long> nodeLoads(Topology topology) {
        Map<String, Long> loads = new TreeMap<>();
        topology.nodes().forEach(n -> loads.put(n.id(), 0L));
        blockToNodes.forEach((blockId, nodes) -> {
            long size = blockSizes.get(blockId);
            for (String nodeId : nodes) {
                if (topology.contains(nodeId)) {
                    loads.merge(nodeId, size, Long::sum);
                }
            }
        });
        return loads;
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {
        private final Map<String, List<String>> blockToNodes = new LinkedHashMap<>();
        private final Map<String, Long> blockSizes = new LinkedHashMap<>();
        private final List<Migration> migrations = new ArrayList<>();

        List<String> nodesOf(String blockId) {
            List<String> nodes = blockToNodes.get(blockId);
            if (nodes == null) {
                throw new IllegalArgumentException("unknown block id: " + blockId);
            }
            return List.copyOf(nodes);
        }

        long sizeOf(String blockId) {
            Long size = blockSizes.get(blockId);
            if (size == null) {
                throw new IllegalArgumentException("unknown block id: " + blockId);
            }
            return size;
        }

        Map<String, List<String>> blockToNodes() {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            blockToNodes.forEach((k, v) -> copy.put(k, List.copyOf(v)));
            return Collections.unmodifiableMap(copy);
        }

        Builder put(String blockId, long size, List<String> nodes) {
            blockToNodes.put(blockId, new ArrayList<>(nodes));
            blockSizes.put(blockId, size);
            return this;
        }

        Builder moveReplica(String blockId, String fromNode, String toNode) {
            List<String> nodes = blockToNodes.get(blockId);
            if (nodes == null) {
                throw new IllegalArgumentException("unknown block id: " + blockId);
            }
            int idx = nodes.indexOf(fromNode);
            if (idx < 0) {
                throw new IllegalArgumentException(
                        "block " + blockId + " has no replica on node " + fromNode);
            }
            nodes.set(idx, toNode);
            migrations.add(new Migration(blockId, fromNode, toNode, blockSizes.get(blockId)));
            return this;
        }

        PlacementPlan build() {
            return new PlacementPlan(blockToNodes, blockSizes, migrations);
        }
    }
}
