package com.example.gsb.placement;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次机架故障模拟的结果。
 *
 * @param failedRackId          失效机架
 * @param survivingReplicas    每个数据组在失效后剩余的可用副本数
 * @param minAvailableRequired  要求的最小可用副本数
 */
public record RackFailureResult(
        String failedRackId,
        Map<String, Integer> survivingReplicas,
        int minAvailableRequired) {

    public RackFailureResult {
        survivingReplicas = Collections.unmodifiableMap(new LinkedHashMap<>(survivingReplicas));
    }

    /** 所有数据组的剩余副本数都不低于最小可用数。 */
    public boolean satisfied() {
        return survivingReplicas.values().stream()
                .allMatch(count -> count >= minAvailableRequired);
    }

    public List<String> violatingGroups() {
        return survivingReplicas.entrySet().stream()
                .filter(entry -> entry.getValue() < minAvailableRequired)
                .map(Map.Entry::getKey)
                .toList();
    }
}
