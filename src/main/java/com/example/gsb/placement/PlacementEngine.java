package com.example.gsb.placement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 放置算法：在既有分配（{@code assigned}，重放时非空）基础上选出其余副本的节点。
 *
 * <p>选择顺序（贪心，逐级比较）：
 * <ol>
 *   <li>该机架当前持有的本组副本数最少 —— 优先把副本分散到不同机架；</li>
 *   <li>机架总负载最低 —— 机架间均衡；</li>
 *   <li>可用域负载最低 —— 机架不足时分散到不同可用域；</li>
 *   <li>节点负载最低、节点 id 字典序 —— 节点间均衡且结果确定。</li>
 * </ol>
 *
 * <p>硬性约束：每个副本独占节点；同一可用域最多一个副本；
 * 每个机架的副本数不超过 {@link PlacementPolicy#maxReplicasPerRack()}。
 */
final class PlacementEngine {

    /**
     * @param totalReplicas 目标副本总数（含 {@code assigned} 中已保留的副本）
     * @param assigned      已确定保留的副本所在节点（全新放置时为空）
     * @param nodeLoad      节点负载工作表，会被本方法就地更新
     * @return 新选出的节点 id 列表（不含 {@code assigned}）
     */
    List<String> place(String groupId, int totalReplicas, List<String> assigned,
                       Map<String, Node> nodes, Map<String, Integer> nodeLoad,
                       PlacementPolicy policy) {
        validateFeasibility(totalReplicas, nodes, policy);
        int needed = totalReplicas - assigned.size();
        if (needed < 0) {
            throw new PlacementException(
                    "数据组 " + groupId + " 已有副本数超过目标副本数 " + totalReplicas);
        }

        Map<String, Integer> rackCount = new HashMap<>();
        Set<String> usedZones = new HashSet<>();
        Set<String> usedNodes = new HashSet<>();
        for (String nodeId : assigned) {
            Node node = nodes.get(nodeId);
            if (node == null) {
                throw new PlacementException(
                        "数据组 " + groupId + " 的副本位于未知节点 " + nodeId);
            }
            rackCount.merge(node.rackId(), 1, Integer::sum);
            usedZones.add(node.zoneKey());
            usedNodes.add(nodeId);
        }

        List<String> selected = new ArrayList<>(needed);
        for (int i = 0; i < needed; i++) {
            Node best = selectBest(nodes, rackCount, usedZones, usedNodes, nodeLoad,
                    policy.maxReplicasPerRack());
            if (best == null) {
                throw new PlacementException("无法为数据组 " + groupId + " 放置第 "
                        + (assigned.size() + i + 1) + " 个副本：没有满足机架/可用域约束的候选节点");
            }
            selected.add(best.id());
            usedNodes.add(best.id());
            usedZones.add(best.zoneKey());
            rackCount.merge(best.rackId(), 1, Integer::sum);
            nodeLoad.merge(best.id(), 1, Integer::sum);
        }
        return selected;
    }

    /** 约束冲突的明确报错：节点数、机架/可用域组合上限、机架故障存活可行性。 */
    private void validateFeasibility(int replicationFactor, Map<String, Node> nodes,
                                     PlacementPolicy policy) {
        long rackCount = nodes.values().stream().map(Node::rackId).distinct().count();
        long zoneCombinations = nodes.values().stream().map(Node::zoneKey).distinct().count();
        if (replicationFactor > nodes.size()) {
            throw new PlacementException(
                    "副本数 " + replicationFactor + " 超过可用节点数 " + nodes.size());
        }
        if (replicationFactor > zoneCombinations) {
            throw new PlacementException("副本数 " + replicationFactor
                    + " 超过机架/可用域组合上限 " + zoneCombinations
                    + "，无法保证每个副本位于不同的可用域");
        }
        int maxPerRack = policy.maxReplicasPerRack();
        if (rackCount * maxPerRack < replicationFactor) {
            throw new PlacementException("机架数量不足：" + rackCount
                    + " 个机架，为满足机架故障后至少保留 "
                    + policy.minAvailableAfterRackFailure() + " 个副本，每个机架最多容纳 "
                    + maxPerRack + " 个副本，无法放置 " + replicationFactor + " 个副本");
        }
    }

    private Node selectBest(Map<String, Node> nodes, Map<String, Integer> rackCount,
                            Set<String> usedZones, Set<String> usedNodes,
                            Map<String, Integer> nodeLoad, int maxPerRack) {
        Map<String, long[]> rackStats = new HashMap<>();
        Map<String, long[]> zoneStats = new HashMap<>();
        for (Node node : nodes.values()) {
            int load = nodeLoad.getOrDefault(node.id(), 0);
            accumulate(rackStats, node.rackId(), load);
            accumulate(zoneStats, node.zoneKey(), load);
        }

        Node best = null;
        for (Node candidate : nodes.values()) {
            if (usedNodes.contains(candidate.id())) {
                continue;
            }
            int candidateRackCount = rackCount.getOrDefault(candidate.rackId(), 0);
            if (candidateRackCount >= maxPerRack || usedZones.contains(candidate.zoneKey())) {
                continue;
            }
            if (best == null || compare(candidate, best, rackCount, rackStats, zoneStats, nodeLoad) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static void accumulate(Map<String, long[]> stats, String key, int load) {
        long[] entry = stats.computeIfAbsent(key, k -> new long[2]);
        entry[0] += load;
        entry[1] += 1;
    }

    /** 每节点平均负载：机架/域内节点数不同时也能公平比较。 */
    private static double averageLoad(Map<String, long[]> stats, String key) {
        long[] entry = stats.get(key);
        return entry == null ? 0.0 : (double) entry[0] / entry[1];
    }

    private int compare(Node a, Node b, Map<String, Integer> rackCount,
                        Map<String, long[]> rackStats, Map<String, long[]> zoneStats,
                        Map<String, Integer> nodeLoad) {
        int cmp = Integer.compare(rackCount.getOrDefault(a.rackId(), 0),
                rackCount.getOrDefault(b.rackId(), 0));
        if (cmp == 0) {
            cmp = Double.compare(averageLoad(rackStats, a.rackId()),
                    averageLoad(rackStats, b.rackId()));
        }
        if (cmp == 0) {
            cmp = Double.compare(averageLoad(zoneStats, a.zoneKey()),
                    averageLoad(zoneStats, b.zoneKey()));
        }
        if (cmp == 0) {
            cmp = Integer.compare(nodeLoad.getOrDefault(a.id(), 0),
                    nodeLoad.getOrDefault(b.id(), 0));
        }
        if (cmp == 0) {
            cmp = a.id().compareTo(b.id());
        }
        return cmp;
    }
}
