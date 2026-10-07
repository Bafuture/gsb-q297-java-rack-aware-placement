package com.example.gsb.placement;

/**
 * 放置约束冲突时抛出的明确异常，例如副本数超过机架/可用域组合上限、
 * 机架数量不足以保证故障后最小可用副本数等。
 */
public class PlacementException extends RuntimeException {

    public PlacementException(String message) {
        super(message);
    }

    public PlacementException(String message, Throwable cause) {
        super(message, cause);
    }
}
