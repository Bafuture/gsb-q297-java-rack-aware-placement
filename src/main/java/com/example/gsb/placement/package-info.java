/**
 * 机架感知的副本放置组件。
 *
 * <p>核心类型：
 * <ul>
 *   <li>{@link com.example.gsb.placement.ReplicaPlacementManager} —— 对外门面：登记节点、
 *       生成放置、机架故障模拟、增删节点重放置、统计查询；</li>
 *   <li>{@link com.example.gsb.placement.PlacementPolicy} —— 副本数、机架故障后最小可用数、
 *       最大均衡偏差、单副本数据量；</li>
 *   <li>{@link com.example.gsb.placement.PlacementEngine} —— 放置算法（包内可见）：
 *       优先跨机架，机架不足时跨可用域，再选负载最低节点；</li>
 *   <li>{@link com.example.gsb.placement.PlacementException} —— 放置约束冲突的明确异常；</li>
 *   <li>{@link com.example.gsb.placement.MigrationReport} /
 *       {@link com.example.gsb.placement.Migration} —— 迁移明细与迁移量；</li>
 *   <li>{@link com.example.gsb.placement.RackFailureResult} —— 机架故障模拟结果；</li>
 *   <li>{@link com.example.gsb.placement.PlacementStatistics} —— 机架/域分布、
 *       均衡偏差与累计迁移量统计。</li>
 * </ul>
 */
package com.example.gsb.placement;
