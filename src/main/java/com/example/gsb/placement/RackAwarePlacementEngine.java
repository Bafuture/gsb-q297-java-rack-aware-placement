package com.example.gsb.placement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 机架感知的副本放置引擎。
 *
 * <p>放置规则：
 * <ol>
 *   <li>副本优先分散到不同机架；机架数不足时，同一机架内的副本分散到不同可用域；
 *       同一可用域内不放置同一数据块的两个副本。</li>
 *   <li>任意单个机架故障后，每个数据块的存活副本数不得少于 {@code minAvailable}。</li>
 *   <li>在以上硬约束满足的前提下，优先选择当前承载数据量最小的节点，
 *       并在批量放置/重放后强制满足均衡偏差比例。</li>
 * </ol>
 */
public final class RackAwarePlacementEngine {

    private static final double EPSILON = 1e-9;

    private final Topology topology;
    private final PlacementConfig config;

    /** 每放置一个数据块递增，用于让“额外副本所在机架”在各机架间轮转。 */
    private int placementRound;

    public RackAwarePlacementEngine(Topology topology, PlacementConfig config) {
        this.topology = topology;
        this.config = config;
    }

    public Topology topology() {
        return topology;
    }

    public PlacementConfig config() {
        return config;
    }

    /**
     * 在空集群上放置单个数据块。单块无法做全局均衡，故只应用机架/域分散与故障存活约束。
     */
    public PlacementPlan place(Block block) {
        Map<String, Long> loads = zeroLoads();
        List<String> nodes = chooseNodes(block.id(), loads);
        return PlacementPlan.builder()
                .put(block.id(), block.size(), nodes)
                .build();
    }

    /**
     * 在空集群上批量放置数据块，放置完成后强制满足均衡偏差比例。
     */
    public PlacementPlan placeAll(List<Block> blocks) {
        PlacementPlan.Builder builder = PlacementPlan.builder();
        Map<String, Long> loads = zeroLoads();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Block block : blocks) {
            if (seen.put(block.id(), Boolean.TRUE) != null) {
                throw new IllegalArgumentException("duplicate block id: " + block.id());
            }
            List<String> nodes = chooseNodes(block.id(), loads);
            builder.put(block.id(), block.size(), nodes);
            for (String nodeId : nodes) {
                loads.merge(nodeId, block.size(), Long::sum);
            }
        }
        enforceBalance(builder, loads);
        return builder.build();
    }

    /**
     * 拓扑变更（节点增删）后的重新放置：
     * 先为落在已删除节点上的副本寻找新位置，再通过最小迁移满足均衡约束。
     * 返回的新方案中包含本次全部迁移记录。
     */
    public PlacementPlan rebalance(PlacementPlan current) {
        PlacementPlan.Builder builder = PlacementPlan.builder();
        current.blockToNodes().forEach((blockId, nodes) ->
                builder.put(blockId, current.sizeOf(blockId), nodes));

        Map<String, Long> loads = current.nodeLoads(topology);

        // 1. 迁离已删除节点上的副本
        for (Map.Entry<String, List<String>> entry : current.blockToNodes().entrySet()) {
            String blockId = entry.getKey();
            for (String nodeId : new ArrayList<>(entry.getValue())) {
                if (topology.contains(nodeId)) {
                    continue;
                }
                List<String> currentNodes = currentNodesOf(builder, blockId);
                String target = bestRelocationTarget(currentNodes, nodeId, loads);
                if (target == null) {
                    throw new PlacementException("节点 " + nodeId + " 已下线，数据块 " + blockId
                            + " 的副本找不到满足机架/可用域约束的承接节点");
                }
                builder.moveReplica(blockId, nodeId, target);
                long size = current.sizeOf(blockId);
                // nodeId 已从拓扑移除，loads 中不再包含它，只需增加目标节点
                loads.merge(target, size, Long::sum);
            }
        }

        // 2. 通过最小必要迁移满足均衡约束
        enforceBalance(builder, loads);
        return builder.build();
    }

    public PlacementStats stats(PlacementPlan plan) {
        return PlacementStats.of(plan, topology);
    }

    // ------------------------------------------------------------------
    // 初始放置
    // ------------------------------------------------------------------

    /** 为一个新数据块选择 replicationFactor 个节点，并更新 loads。 */
    private List<String> chooseNodes(String blockId, Map<String, Long> loads) {
        int round = placementRound++;
        Map<String, List<Node>> byRack = topology.nodesByRack();
        if (byRack.isEmpty()) {
            throw new PlacementException("集群中没有可用节点，无法放置数据块 " + blockId);
        }
        int rf = config.replicationFactor();
        int totalNodes = topology.size();
        if (rf > totalNodes) {
            throw new PlacementException("副本数 " + rf + " 超过可用节点数 " + totalNodes
                    + "，无法放置数据块 " + blockId);
        }

        Map<String, Integer> rackCounts = assignRacks(blockId, byRack, round);

        List<String> chosen = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : rackCounts.entrySet()) {
            String rack = entry.getKey();
            int count = entry.getValue();
            if (count == 0) {
                continue;
            }
            chosen.addAll(pickNodesInRack(blockId, rack, count, loads));
        }
        return chosen;
    }

    /**
     * 决定每个机架分到几个副本：先按机架轮转分散（受各机架可用域数量上限约束），
     * 再调整以满足机架故障后的最小可用副本数。
     */
    private Map<String, Integer> assignRacks(String blockId, Map<String, List<Node>> byRack, int round) {
        Map<String, java.util.Set<String>> zonesByRack = topology.zonesByRack();
        int rf = config.replicationFactor();
        int totalZones = zonesByRack.values().stream().mapToInt(java.util.Set::size).sum();
        if (rf > totalZones) {
            throw new PlacementException("副本数 " + rf + " 超过可用机架与可用域的组合上限（共 "
                    + byRack.size() + " 个机架、" + totalZones
                    + " 个可用域），无法放置数据块 " + blockId);
        }
        // 机架故障存活约束：任意机架故障后剩余副本 >= minAvailable，
        // 等价于每个机架至多容纳 rf - minAvailable 个副本。
        int perRackSurvivorCap = rf - config.minAvailable();
        Map<String, Integer> capacity = new TreeMap<>();
        zonesByRack.forEach((rack, zones) ->
                capacity.put(rack, Math.min(zones.size(), perRackSurvivorCap)));
        int totalCapacity = capacity.values().stream().mapToInt(Integer::intValue).sum();
        if (rf > totalCapacity) {
            throw new PlacementException("现有机架/可用域拓扑无法满足 minAvailable="
                    + config.minAvailable() + "：任意机架故障后至少保留 " + config.minAvailable()
                    + " 个副本，要求每个机架至多容纳 " + perRackSurvivorCap
                    + " 个副本，但各机架容量合计仅 " + totalCapacity + " < 副本数 " + rf
                    + "（数据块 " + blockId + "）");
        }

        // 可用域多的机架优先吸收额外副本，其次节点多的，最后按 id 保证确定性；
        // 起始机架按放置轮次轮转，使额外副本在各机架间均衡。
        List<String> rackOrder = new ArrayList<>(byRack.keySet());
        rackOrder.sort(Comparator
                .<String>comparingInt(r -> zonesByRack.get(r).size()).reversed()
                .thenComparing(Comparator.<String>comparingInt(r -> byRack.get(r).size()).reversed())
                .thenComparing(Comparator.naturalOrder()));

        Map<String, Integer> counts = new TreeMap<>();
        rackOrder.forEach(r -> counts.put(r, 0));
        int rackBase = round % rackOrder.size();
        for (int i = 0; i < rf; i++) {
            for (int offset = 0; offset < rackOrder.size(); offset++) {
                String rack = rackOrder.get((rackBase + i + offset) % rackOrder.size());
                if (counts.get(rack) < capacity.get(rack)) {
                    counts.merge(rack, 1, Integer::sum);
                    break;
                }
            }
        }
        return counts;
    }

    /** 在指定机架内选择 count 个节点：分散到不同可用域，域内选负载最轻的节点。 */
    private List<String> pickNodesInRack(String blockId, String rack, int count, Map<String, Long> loads) {
        Map<String, List<Node>> zones = topology.nodesByRackAndZone().get(rack);
        if (zones == null || count > zones.size()) {
            throw new PlacementException("机架 " + rack + " 的可用域数量不足，无法放置数据块 " + blockId);
        }
        List<String> chosen = new ArrayList<>();
        List<String> remainingZones = new ArrayList<>(zones.keySet());
        for (int i = 0; i < count; i++) {
            // 选当前负载最轻的可用域
            String zone = remainingZones.stream()
                    .min(Comparator.comparingLong((String z) -> zoneLoad(zones.get(z), loads))
                            .thenComparing(Comparator.naturalOrder()))
                    .orElseThrow();
            remainingZones.remove(zone);
            // 域内选负载最轻的节点
            Node node = zones.get(zone).stream()
                    .min(Comparator.comparingLong((Node n) -> loads.getOrDefault(n.id(), 0L))
                            .thenComparing(Node::id))
                    .orElseThrow();
            chosen.add(node.id());
        }
        return chosen;
    }

    // ------------------------------------------------------------------
    // 均衡
    // ------------------------------------------------------------------

    /**
     * 通过贪心迁移使 (max-min)/avg <= balanceRatio。
     * 每步在所有满足机架/域硬约束的单副本迁移中，选择使偏差下降最多的最小迁移；
     * 若偏差仍超标且不存在任何可行迁移，则抛出约束冲突异常。
     */
    private void enforceBalance(PlacementPlan.Builder builder, Map<String, Long> loads) {
        double ratio = config.balanceRatio();
        while (PlacementStats.balanceDeviation(loads) > ratio + EPSILON) {
            Migration move = findBalanceMove(builder, loads);
            if (move == null) {
                throw new PlacementException(String.format(
                        "均衡约束冲突：当前偏差 %.4f 超过配置比例 %.4f，"
                                + "且不存在满足机架/可用域约束的副本迁移可继续降低偏差",
                        PlacementStats.balanceDeviation(loads), ratio));
            }
            builder.moveReplica(move.blockId(), move.fromNode(), move.toNode());
            loads.merge(move.fromNode(), -move.bytes(), Long::sum);
            loads.merge(move.toNode(), move.bytes(), Long::sum);
        }
    }

    /**
     * 寻找一步严格降低负载差距的迁移：从最满载节点向最空闲节点移动一个副本，
     * 仅当副本大小严格小于两端负载差时才迁移（保证负载平方和严格下降、算法必然终止），
     * 且迁移后仍满足机架/可用域硬约束。块大小优先接近差距的一半，以最少迁移次数收敛。
     */
    private Migration findBalanceMove(PlacementPlan.Builder builder, Map<String, Long> loads) {
        List<String> sources = new ArrayList<>(loads.keySet());
        sources.sort(Comparator.<String>comparingLong(loads::get).reversed()
                .thenComparing(Comparator.naturalOrder()));
        List<String> targets = new ArrayList<>(loads.keySet());
        targets.sort(Comparator.<String>comparingLong(loads::get).thenComparing(Comparator.naturalOrder()));

        Map<String, List<String>> blocks = blocksOf(builder);
        for (String from : sources) {
            for (String to : targets) {
                long gap = loads.get(from) - loads.get(to);
                if (gap <= 0) {
                    break;
                }
                Migration best = null;
                for (Map.Entry<String, List<String>> entry : blocks.entrySet()) {
                    String blockId = entry.getKey();
                    List<String> nodes = entry.getValue();
                    if (!nodes.contains(from) || nodes.contains(to)) {
                        continue;
                    }
                    long size = builder.sizeOf(blockId);
                    if (size >= gap) {
                        continue;
                    }
                    List<String> replaced = new ArrayList<>(nodes);
                    replaced.set(replaced.indexOf(from), to);
                    if (!satisfiesRackRules(replaced)) {
                        continue;
                    }
                    if (best == null
                            || Math.abs(size - gap / 2.0) < Math.abs(best.bytes() - gap / 2.0)
                            || (Math.abs(size - gap / 2.0) == Math.abs(best.bytes() - gap / 2.0)
                            && blockId.compareTo(best.blockId()) < 0)) {
                        best = new Migration(blockId, from, to, size);
                    }
                }
                if (best != null) {
                    return best;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 约束校验
    // ------------------------------------------------------------------

    /**
     * 校验一组节点是否满足：同一机架内副本数不超过该机架可用域数，
     * 且去掉副本最多的机架后剩余副本数 >= minAvailable。
     */
    boolean satisfiesRackRules(List<String> nodeIds) {
        Map<String, Integer> perRack = new TreeMap<>();
        Map<String, java.util.Set<String>> zonesUsed = new TreeMap<>();
        for (String nodeId : nodeIds) {
            Node node = topology.node(nodeId);
            perRack.merge(node.rack(), 1, Integer::sum);
            zonesUsed.computeIfAbsent(node.rack(), k -> new java.util.TreeSet<>()).add(node.zone());
        }
        int maxInRack = 0;
        for (Map.Entry<String, Integer> entry : perRack.entrySet()) {
            String rack = entry.getKey();
            int count = entry.getValue();
            if (count > topology.zonesByRack().get(rack).size()) {
                return false;
            }
            if (zonesUsed.get(rack).size() != count) {
                return false; // 同一可用域内出现两个副本
            }
            maxInRack = Math.max(maxInRack, count);
        }
        return nodeIds.size() - maxInRack >= config.minAvailable();
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private String bestRelocationTarget(List<String> currentNodes, String from, Map<String, Long> loads) {
        String best = null;
        long bestLoad = Long.MAX_VALUE;
        for (Node candidate : topology.nodes()) {
            String to = candidate.id();
            if (currentNodes.contains(to)) {
                continue;
            }
            List<String> replaced = new ArrayList<>(currentNodes);
            replaced.set(replaced.indexOf(from), to);
            if (!satisfiesRackRules(replaced)) {
                continue;
            }
            long load = loads.getOrDefault(to, 0L);
            if (best == null || load < bestLoad
                    || (load == bestLoad && to.compareTo(best) < 0)) {
                best = to;
                bestLoad = load;
            }
        }
        return best;
    }

    private Map<String, Long> zeroLoads() {
        Map<String, Long> loads = new TreeMap<>();
        topology.nodes().forEach(n -> loads.put(n.id(), 0L));
        return loads;
    }

    private static long zoneLoad(List<Node> nodes, Map<String, Long> loads) {
        return nodes.stream().mapToLong(n -> loads.getOrDefault(n.id(), 0L)).sum();
    }

    private static List<String> currentNodesOf(PlacementPlan.Builder builder, String blockId) {
        return new ArrayList<>(builder.nodesOf(blockId));
    }

    private static Map<String, List<String>> blocksOf(PlacementPlan.Builder builder) {
        return builder.blockToNodes();
    }
}
