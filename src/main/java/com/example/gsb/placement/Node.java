package com.example.gsb.placement;

import java.util.Objects;

/**
 * 存储节点：归属于某个机架（rack），机架内再划分可用域（zone，例如不同交换机）。
 * 可用域的标识在机架内唯一，全局用 {@code rackId + "/" + zoneId} 表示。
 */
public record Node(String id, String rackId, String zoneId) {

    public Node {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(rackId, "rackId");
        Objects.requireNonNull(zoneId, "zoneId");
        if (id.isBlank() || rackId.isBlank() || zoneId.isBlank()) {
            throw new IllegalArgumentException("节点 id、机架 id、可用域 id 均不能为空");
        }
    }

    /** 全局唯一的可用域标识：{@code 机架/可用域}。 */
    public String zoneKey() {
        return rackId + "/" + zoneId;
    }
}
