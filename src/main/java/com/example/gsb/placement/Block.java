package com.example.gsb.placement;

/**
 * 待放置的数据块。
 *
 * @param id   数据块唯一标识
 * @param size 数据量（任意一致的单位，如 MB）
 */
public record Block(String id, long size) {

    public Block {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("block id must not be blank");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("block size must be > 0");
        }
    }

    public static Block of(String id, long size) {
        return new Block(id, size);
    }
}
