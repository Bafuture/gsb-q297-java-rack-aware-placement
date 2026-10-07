package com.example.gsb.placement;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 放置统计快照。
 *
 * @param rackDistribution           机架 -> 承载副本数
 * @param zoneDistribution           可用域（机架/域）-> 承载副本数
 * @param nodeLoad                   节点 -> 承载副本数（含当前无负载的节点）
 * @param averageLoad                节点平均负载
 * @param balanceDeviation           均衡偏差 = (最大节点负载 - 最小节点负载) / 平均负载
 * @param totalMigratedReplicas      自管理器创建以来累计迁移副本数
 * @param totalMigratedDataVolume    累计迁移数据量（字节）
 */
public record PlacementStatistics(
        Map<String, Integer> rackDistribution,
        Map<String, Integer> zoneDistribution,
        Map<String, Integer> nodeLoad,
        double averageLoad,
        double balanceDeviation,
        long totalMigratedReplicas,
        long totalMigratedDataVolume) {

    public PlacementStatistics {
        rackDistribution = Collections.unmodifiableMap(new LinkedHashMap<>(rackDistribution));
        zoneDistribution = Collections.unmodifiableMap(new LinkedHashMap<>(zoneDistribution));
        nodeLoad = Collections.unmodifiableMap(new LinkedHashMap<>(nodeLoad));
    }
}
