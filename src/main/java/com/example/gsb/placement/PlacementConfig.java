package com.example.gsb.placement;

/**
 * 放置策略配置。
 *
 * @param replicationFactor 每个数据块的副本数
 * @param minAvailable      任意单机架故障后必须保留的最小可用副本数
 * @param balanceRatio      各节点承载数据量（max-min）相对平均承载量允许的最大偏差比例
 */
public record PlacementConfig(int replicationFactor, int minAvailable, double balanceRatio) {

    public PlacementConfig {
        if (replicationFactor < 1) {
            throw new IllegalArgumentException("replicationFactor must be >= 1");
        }
        if (minAvailable < 1) {
            throw new IllegalArgumentException("minAvailable must be >= 1");
        }
        if (minAvailable > replicationFactor) {
            throw new IllegalArgumentException("minAvailable must be <= replicationFactor");
        }
        if (balanceRatio < 0 || Double.isNaN(balanceRatio) || Double.isInfinite(balanceRatio)) {
            throw new IllegalArgumentException("balanceRatio must be a finite non-negative number");
        }
    }

    public static PlacementConfig of(int replicationFactor, int minAvailable, double balanceRatio) {
        return new PlacementConfig(replicationFactor, minAvailable, balanceRatio);
    }
}
