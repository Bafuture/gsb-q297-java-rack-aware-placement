package com.example.gsb.placement;

import java.util.List;
import java.util.Objects;

/**
 * 一个数据组的放置方案：{@code nodeIds} 为各副本（按副本序号）所在节点。
 */
public record Placement(String groupId, List<String> nodeIds) {

    public Placement {
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(nodeIds, "nodeIds");
        nodeIds = List.copyOf(nodeIds);
    }

    public int replicas() {
        return nodeIds.size();
    }
}
