package com.example.gsb.placement;

/**
 * 一次副本迁移记录。
 *
 * @param blockId  数据块 id
 * @param fromNode 迁出节点（新增节点场景下为数据来源节点）
 * @param toNode   迁入节点
 * @param bytes    迁移的数据量
 */
public record Migration(String blockId, String fromNode, String toNode, long bytes) {

    @Override
    public String toString() {
        return blockId + ": " + fromNode + " -> " + toNode + " (" + bytes + ")";
    }
}
