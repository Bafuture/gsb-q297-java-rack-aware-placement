package com.example.gsb.placement;

/**
 * 放置约束无法满足时抛出，例如副本数超过可用机架/域/节点的组合上限、
 * 或机架故障后无法满足最小可用副本数。
 */
public class PlacementException extends RuntimeException {

    public PlacementException(String message) {
        super(message);
    }
}
