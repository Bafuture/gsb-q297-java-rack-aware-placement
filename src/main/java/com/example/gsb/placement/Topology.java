package com.example.gsb.placement;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 集群拓扑：节点及其机架/可用域归属的注册表。
 */
public final class Topology {

    private final Map<String, Node> nodes = new LinkedHashMap<>();

    public void addNode(Node node) {
        if (nodes.containsKey(node.id())) {
            throw new IllegalArgumentException("duplicate node id: " + node.id());
        }
        nodes.put(node.id(), node);
    }

    public void removeNode(String nodeId) {
        if (nodes.remove(nodeId) == null) {
            throw new IllegalArgumentException("unknown node id: " + nodeId);
        }
    }

    public boolean contains(String nodeId) {
        return nodes.containsKey(nodeId);
    }

    public Node node(String nodeId) {
        Node node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("unknown node id: " + nodeId);
        }
        return node;
    }

    public List<Node> nodes() {
        return List.copyOf(nodes.values());
    }

    public int size() {
        return nodes.size();
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** 机架 -> 该机架上的节点（按节点 id 排序）。 */
    public Map<String, List<Node>> nodesByRack() {
        Map<String, List<Node>> byRack = new TreeMap<>();
        for (Node node : nodes.values()) {
            byRack.computeIfAbsent(node.rack(), k -> new ArrayList<>()).add(node);
        }
        byRack.values().forEach(list -> list.sort((a, b) -> a.id().compareTo(b.id())));
        return Collections.unmodifiableMap(byRack);
    }

    /** 机架 -> 该机架内的可用域集合。 */
    public Map<String, Set<String>> zonesByRack() {
        Map<String, Set<String>> result = new TreeMap<>();
        for (Node node : nodes.values()) {
            result.computeIfAbsent(node.rack(), k -> new TreeSet<>()).add(node.zone());
        }
        return Collections.unmodifiableMap(result);
    }

    /** 机架 -> 可用域 -> 节点列表。 */
    public Map<String, Map<String, List<Node>>> nodesByRackAndZone() {
        Map<String, Map<String, List<Node>>> result = new TreeMap<>();
        for (Node node : nodes.values()) {
            result.computeIfAbsent(node.rack(), k -> new TreeMap<>())
                    .computeIfAbsent(node.zone(), k -> new ArrayList<>())
                    .add(node);
        }
        result.values().forEach(zones -> zones.values()
                .forEach(list -> list.sort((a, b) -> a.id().compareTo(b.id()))));
        return Collections.unmodifiableMap(result);
    }

    public Optional<Node> find(String nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }
}
