package com.example.gsb.placement;

import java.util.List;

/**
 * 一次重新放置（节点增删 / 显式 rebalance）产生的迁移明细与迁移量统计。
 */
public final class MigrationReport {

    private final List<Migration> migrations;
    private final long bytesPerReplica;

    public MigrationReport(List<Migration> migrations, long bytesPerReplica) {
        this.migrations = List.copyOf(migrations);
        this.bytesPerReplica = bytesPerReplica;
    }

    public List<Migration> migrations() {
        return migrations;
    }

    public int migratedReplicas() {
        return migrations.size();
    }

    /** 迁移数据总量（字节）= 迁移副本数 × 单副本数据量。 */
    public long migratedDataVolume() {
        return (long) migrations.size() * bytesPerReplica;
    }

    public boolean isEmpty() {
        return migrations.isEmpty();
    }
}
