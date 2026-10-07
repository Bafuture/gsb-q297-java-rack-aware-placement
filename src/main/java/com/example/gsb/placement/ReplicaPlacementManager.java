package com.example.gsb.placement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * 机架感知的副本放置管理器（线程安全，所有公开方法加锁）。
 *
 * <p>职责：
 * <ul>
 *   <li>登记节点（机架 + 机架内可用域）；</li>
 *   <li>按 {@link PlacementPolicy} 生成放置方案：机架优先、域其次、负载最低节点；</li>
 *   <li>机架故障模拟（指定机架或随机机架），校验最小可用副本数；</li>
 *   <li>节点增删后的最小迁移重放置，并返回迁移量统计；</li>
 *   <li>提供机架/域分布、均衡偏差、累计迁移量等统计。</li>
 * </ul>
 */
public final class ReplicaPlacementManager {

    private final PlacementPolicy policy;
    private final PlacementEngine engine = new PlacementEngine();
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Placement> placements = new LinkedHashMap<>();
    private long totalMigratedReplicas;

    public ReplicaPlacementManager(PlacementPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public PlacementPolicy policy() {
        return policy;
    }

    // ------------------------------------------------------------------
    // 节点登记
    // ------------------------------------------------------------------

    /** 登记新节点，并在必要时做最小迁移以恢复均衡。 */
    public synchronized MigrationReport addNode(Node node) {
        Objects.requireNonNull(node, "node");
        if (nodes.containsKey(node.id())) {
            throw new IllegalArgumentException("节点已存在: " + node.id());
        }
        nodes.put(node.id(), node);
        return rebalanceInternal();
    }

    /**
     * 删除节点：其上副本会被优先迁移到满足约束的最低负载节点，随后恢复均衡。
     * 若删除后无法满足放置约束，会抛出 {@link PlacementException} 且拓扑与放置回滚。
     */
    public synchronized MigrationReport removeNode(String nodeId) {
        Node removed = nodes.remove(nodeId);
        if (removed == null) {
            throw new IllegalArgumentException("未知节点: " + nodeId);
        }
        try {
            return rebalanceInternal();
        } catch (PlacementException e) {
            nodes.put(nodeId, removed);
            throw e;
        }
    }

    public synchronized Node node(String nodeId) {
        return nodes.get(nodeId);
    }

    public synchronized Collection<Node> nodes() {
        return List.copyOf(nodes.values());
    }

    // ------------------------------------------------------------------
    // 放置
    // ------------------------------------------------------------------

    /** 为一个数据组生成放置方案。约束冲突时抛出 {@link PlacementException}。 */
    public synchronized Placement place(String groupId) {
        Objects.requireNonNull(groupId, "groupId");
        if (placements.containsKey(groupId)) {
            throw new PlacementException("数据组已存在: " + groupId);
        }
        Map<String, Integer> load = computeLoads();
        List<String> selected = engine.place(groupId, policy.replicationFactor(),
                List.of(), nodes, load, policy);
        Placement placement = new Placement(groupId, selected);
        placements.put(groupId, placement);
        return placement;
    }

    public synchronized Placement placementOf(String groupId) {
        Placement placement = placements.get(groupId);
        if (placement == null) {
            throw new IllegalArgumentException("未知数据组: " + groupId);
        }
        return placement;
    }

    public synchronized List<Placement> placements() {
        return List.copyOf(placements.values());
    }

    public synchronized boolean remove(String groupId) {
        return placements.remove(groupId) != null;
    }

    /** 显式触发一次最小迁移重平衡（通常无需手动调用，节点增删时已自动执行）。 */
    public synchronized MigrationReport rebalance() {
        return rebalanceInternal();
    }

    // ------------------------------------------------------------------
    // 机架故障模拟
    // ------------------------------------------------------------------

    /** 模拟指定机架整体失效，返回各数据组的剩余副本数（不改变当前放置）。 */
    public synchronized RackFailureResult simulateRackFailure(String rackId) {
        Set<String> deadNodes = new LinkedHashSet<>();
        for (Node node : nodes.values()) {
            if (node.rackId().equals(rackId)) {
                deadNodes.add(node.id());
            }
        }
        if (deadNodes.isEmpty()) {
            throw new IllegalArgumentException("未知机架: " + rackId);
        }
        Map<String, Integer> surviving = new LinkedHashMap<>();
        for (Placement placement : placements.values()) {
            int alive = 0;
            for (String nodeId : placement.nodeIds()) {
                if (!deadNodes.contains(nodeId)) {
                    alive++;
                }
            }
            surviving.put(placement.groupId(), alive);
        }
        return new RackFailureResult(rackId, surviving, policy.minAvailableAfterRackFailure());
    }

    /** 随机让一个机架失效，随机源由调用方注入（测试可固定种子）。 */
    public synchronized RackFailureResult simulateRandomRackFailure(Random random) {
        List<String> racks = nodes.values().stream().map(Node::rackId).distinct().toList();
        if (racks.isEmpty()) {
            throw new IllegalStateException("集群中没有任何机架，无法模拟机架故障");
        }
        return simulateRackFailure(racks.get(random.nextInt(racks.size())));
    }

    // ------------------------------------------------------------------
    // 统计
    // ------------------------------------------------------------------

    public synchronized PlacementStatistics statistics() {
        Map<String, Integer> rackDistribution = new TreeMap<>();
        Map<String, Integer> zoneDistribution = new TreeMap<>();
        Map<String, Integer> nodeLoad = new TreeMap<>();
        nodes.keySet().forEach(id -> nodeLoad.put(id, 0));

        for (Placement placement : placements.values()) {
            for (String nodeId : placement.nodeIds()) {
                Node node = nodes.get(nodeId);
                if (node == null) {
                    continue;
                }
                rackDistribution.merge(node.rackId(), 1, Integer::sum);
                zoneDistribution.merge(node.zoneKey(), 1, Integer::sum);
                nodeLoad.merge(nodeId, 1, Integer::sum);
            }
        }

        double average = nodeLoad.values().stream().mapToInt(Integer::intValue).average().orElse(0.0);
        int max = nodeLoad.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int min = nodeLoad.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        double deviation = average <= 0.0 ? 0.0 : (max - min) / average;
        return new PlacementStatistics(
                rackDistribution, zoneDistribution, nodeLoad, average, deviation,
                totalMigratedReplicas, totalMigratedReplicas * policy.replicaSizeBytes());
    }

    // ------------------------------------------------------------------
    // 内部：最小迁移重放置
    // ------------------------------------------------------------------

    private MigrationReport rebalanceInternal() {
        List<Migration> migrations = new ArrayList<>();
        if (!placements.isEmpty()) {
            relocateOrphanedReplicas(migrations);
            restoreBalance(migrations);
        }
        totalMigratedReplicas += migrations.size();
        return new MigrationReport(migrations, policy.replicaSizeBytes());
    }

    /** 第一阶段：把位于已删除节点上的副本迁到满足约束的最低负载节点（先规划后提交）。 */
    private void relocateOrphanedReplicas(List<Migration> migrations) {
        Map<String, Integer> workingLoad = computeLoads();
        Map<String, List<String>> planned = new LinkedHashMap<>();

        for (Placement placement : placements.values()) {
            List<String> current = placement.nodeIds();
            List<Integer> orphanIndexes = new ArrayList<>();
            List<String> kept = new ArrayList<>();
            for (int i = 0; i < current.size(); i++) {
                if (nodes.containsKey(current.get(i))) {
                    kept.add(current.get(i));
                } else {
                    orphanIndexes.add(i);
                }
            }
            if (orphanIndexes.isEmpty()) {
                continue;
            }
            List<String> replacements = engine.place(placement.groupId(), current.size(),
                    kept, nodes, workingLoad, policy);
            List<String> updated = new ArrayList<>(current);
            for (int k = 0; k < orphanIndexes.size(); k++) {
                int index = orphanIndexes.get(k);
                updated.set(index, replacements.get(k));
                migrations.add(new Migration(placement.groupId(), index,
                        current.get(index), replacements.get(k)));
            }
            planned.put(placement.groupId(), updated);
        }

        planned.forEach((groupId, nodeIds) ->
                placements.put(groupId, new Placement(groupId, nodeIds)));
    }

    /**
     * 第二阶段：在约束允许的前提下，把副本从最高负载节点迁往最低负载节点，
     * 直到 (max - min) &le; floor(deviation × avg) 或不存在可改善的迁移为止。
     */
    private void restoreBalance(List<Migration> migrations) {
        if (nodes.isEmpty()) {
            return;
        }
        long totalReplicas = placements.values().stream().mapToLong(Placement::replicas).sum();
        long guard = totalReplicas * 2 + 16;
        while (guard-- > 0) {
            Map<String, Integer> load = computeLoads();
            double average = load.values().stream().mapToInt(Integer::intValue).average().orElse(0.0);
            if (average <= 0.0) {
                return;
            }
            long allowedDiff = Math.max(1L, (long) Math.floor(policy.maxBalanceDeviation() * average));
            String maxNode = null;
            String minNode = null;
            for (String id : load.keySet()) {
                if (maxNode == null || load.get(id) > load.get(maxNode)) {
                    maxNode = id;
                }
                if (minNode == null || load.get(id) < load.get(minNode)) {
                    minNode = id;
                }
            }
            if (load.get(maxNode) - load.get(minNode) <= allowedDiff) {
                return;
            }
            if (!moveOneReplica(load, migrations)) {
                return;
            }
        }
    }

    private boolean moveOneReplica(Map<String, Integer> load, List<Migration> migrations) {
        List<String> byLoadDesc = new ArrayList<>(load.keySet());
        byLoadDesc.sort((a, b) -> {
            int cmp = Integer.compare(load.get(b), load.get(a));
            return cmp != 0 ? cmp : a.compareTo(b);
        });
        List<String> byLoadAsc = new ArrayList<>(load.keySet());
        byLoadAsc.sort((a, b) -> {
            int cmp = Integer.compare(load.get(a), load.get(b));
            return cmp != 0 ? cmp : a.compareTo(b);
        });

        for (String source : byLoadDesc) {
            for (String target : byLoadAsc) {
                if (load.get(target) >= load.get(source) - 1) {
                    continue;
                }
                for (Placement placement : placements.values()) {
                    int index = placement.nodeIds().indexOf(source);
                    if (index < 0 || !canMove(placement, index, target)) {
                        continue;
                    }
                    List<String> updated = new ArrayList<>(placement.nodeIds());
                    updated.set(index, target);
                    placements.put(placement.groupId(), new Placement(placement.groupId(), updated));
                    migrations.add(new Migration(placement.groupId(), index, source, target));
                    load.merge(source, -1, Integer::sum);
                    load.merge(target, 1, Integer::sum);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean canMove(Placement placement, int replicaIndex, String targetNodeId) {
        Node target = nodes.get(targetNodeId);
        if (target == null) {
            return false;
        }
        List<String> current = placement.nodeIds();
        int targetRackCount = 0;
        for (int i = 0; i < current.size(); i++) {
            if (i == replicaIndex) {
                continue;
            }
            Node other = nodes.get(current.get(i));
            if (other == null) {
                return false;
            }
            if (current.get(i).equals(targetNodeId) || other.zoneKey().equals(target.zoneKey())) {
                return false;
            }
            if (other.rackId().equals(target.rackId())) {
                targetRackCount++;
            }
        }
        return targetRackCount < policy.maxReplicasPerRack();
    }

    private Map<String, Integer> computeLoads() {
        Map<String, Integer> load = new LinkedHashMap<>();
        nodes.keySet().forEach(id -> load.put(id, 0));
        for (Placement placement : placements.values()) {
            for (String nodeId : placement.nodeIds()) {
                if (load.containsKey(nodeId)) {
                    load.merge(nodeId, 1, Integer::sum);
                }
            }
        }
        return load;
    }
}
