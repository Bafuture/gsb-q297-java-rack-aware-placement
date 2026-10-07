package com.example.gsb.placement;

import java.util.Objects;

/**
 * 一次副本迁移：数据组 {@code groupId} 的第 {@code replicaIndex} 个副本
 * 从 {@code fromNodeId} 迁移到 {@code toNodeId}。
 */
public record Migration(String groupId, int replicaIndex, String fromNodeId, String toNodeId) {

    public Migration {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(fromNodeId, "fromNodeId");
        Objects.requireNonNull(toNodeId, "toNodeId");
    }
}
