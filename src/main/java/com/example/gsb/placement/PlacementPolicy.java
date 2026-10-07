package com.example.gsb.placement;

/**
 * 放置策略。
 *
 * @param replicationFactor                  每个数据组的副本数
 * @param minAvailableAfterRackFailure       单个机架故障后每个数据组至少保留的可用副本数
 * @param maxBalanceDeviation                节点负载允许的最大均衡偏差，例如 0.2 表示
 *                                           (最高负载 - 最低负载) / 平均负载 &le; 20%
 * @param replicaSizeBytes                   单个副本承载的数据量（字节），用于迁移量统计
 */
public record PlacementPolicy(
        int replicationFactor,
        int minAvailableAfterRackFailure,
        double maxBalanceDeviation,
        long replicaSizeBytes) {

    public PlacementPolicy {
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("副本数必须 >= 1，实际为 " + replicationFactor);
        }
        if (minAvailableAfterRackFailure < 0
                || minAvailableAfterRackFailure >= replicationFactor) {
            throw new IllegalArgumentException(
                    "机架故障后的最小可用副本数必须满足 0 <= minAvailable < replicationFactor，实际为 "
                            + minAvailableAfterRackFailure + "（副本数 " + replicationFactor + "）");
        }
        if (Double.isNaN(maxBalanceDeviation) || maxBalanceDeviation < 0) {
            throw new IllegalArgumentException("最大均衡偏差必须 >= 0，实际为 " + maxBalanceDeviation);
        }
        if (replicaSizeBytes < 1) {
            throw new IllegalArgumentException("单副本数据量必须 >= 1 字节，实际为 " + replicaSizeBytes);
        }
    }

    /** 使用默认单副本 1 字节的便捷构造。 */
    public static PlacementPolicy of(int replicationFactor,
                                     int minAvailableAfterRackFailure,
                                     double maxBalanceDeviation) {
        return new PlacementPolicy(replicationFactor, minAvailableAfterRackFailure,
                maxBalanceDeviation, 1L);
    }

    /**
     * 单个机架允许承载的同一数据组副本上限。
     * 机架故障最多损失这么多副本，其余 {@code replicationFactor - maxPerRack}
     * 即 {@code minAvailableAfterRackFailure} 个副本必然存活。
     */
    public int maxReplicasPerRack() {
        return replicationFactor - minAvailableAfterRackFailure;
    }
}
