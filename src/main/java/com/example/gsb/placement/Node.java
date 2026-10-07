package com.example.gsb.placement;

import java.util.Objects;

/**
 * 集群中的一个存储节点，归属于某个机架上的某个可用域（如不同 ToR 交换机）。
 */
public final class Node {

    private final String id;
    private final String rack;
    private final String zone;

    public Node(String id, String rack, String zone) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("node id must not be blank");
        }
        if (rack == null || rack.isBlank()) {
            throw new IllegalArgumentException("rack must not be blank for node " + id);
        }
        if (zone == null || zone.isBlank()) {
            throw new IllegalArgumentException("zone must not be blank for node " + id);
        }
        this.id = id;
        this.rack = rack;
        this.zone = zone;
    }

    public String id() {
        return id;
    }

    public String rack() {
        return rack;
    }

    public String zone() {
        return zone;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Node node)) {
            return false;
        }
        return id.equals(node.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return id + "(" + rack + "/" + zone + ")";
    }
}
