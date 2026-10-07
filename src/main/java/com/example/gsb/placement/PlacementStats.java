package com.example.gsb.placement;

import java.util.Map;
import java.util.TreeMap;

/**
 * 放置方案的统计信息。
 *
 * @param rackDistribution     机架 -> 该机架上的副本数
 * @param zoneDistribution     "机架/可用域" -> 该域上的副本数
 * @param nodeLoads            节点 -> 承载的数据量
 * @param balanceDeviationRatio 均衡偏差：(max-min)/avg，0 表示完全均衡
 * @param migratedBytes        本次操作迁移的总数据量
 */
public record PlacementStats(Map<String, Long> rackDistribution,
                             Map<String, Long> zoneDistribution,
                             Map<String, Long> nodeLoads,
                             double balanceDeviationRatio,
                             long migratedBytes) {

    public PlacementStats {
        rackDistribution = Map.copyOf(rackDistribution);
        zoneDistribution = Map.copyOf(zoneDistribution);
        nodeLoads = Map.copyOf(nodeLoads);
    }

    public static PlacementStats of(PlacementPlan plan, Topology topology) {
        Map<String, Long> byRack = new TreeMap<>();
        Map<String, Long> byZone = new TreeMap<>();
        plan.blockToNodes().forEach((blockId, nodeIds) -> {
            for (String nodeId : nodeIds) {
                Node node = topology.node(nodeId);
                byRack.merge(node.rack(), 1L, Long::sum);
                byZone.merge(node.rack() + "/" + node.zone(), 1L, Long::sum);
            }
        });
        Map<String, Long> loads = plan.nodeLoads(topology);
        return new PlacementStats(byRack, byZone, loads,
                balanceDeviation(loads), plan.migratedBytes());
    }

    /** 均衡偏差：(max-min)/avg；空集群或无数据时为 0。 */
    public static double balanceDeviation(Map<String, Long> loads) {
        if (loads.isEmpty()) {
            return 0.0;
        }
        long max = loads.values().stream().mapToLong(Long::longValue).max().orElse(0);
        long min = loads.values().stream().mapToLong(Long::longValue).min().orElse(0);
        double avg = loads.values().stream().mapToLong(Long::longValue).average().orElse(0);
        if (avg == 0) {
            return 0.0;
        }
        return (max - min) / avg;
    }

    @Override
    public String toString() {
        return "PlacementStats{rackDistribution=" + new TreeMap<>(rackDistribution)
                + ", zoneDistribution=" + new TreeMap<>(zoneDistribution)
                + ", nodeLoads=" + new TreeMap<>(nodeLoads)
                + ", balanceDeviationRatio=" + String.format("%.4f", balanceDeviationRatio)
                + ", migratedBytes=" + migratedBytes + '}';
    }
}
