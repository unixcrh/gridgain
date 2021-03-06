/* 
 Copyright (C) GridGain Systems. All Rights Reserved.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.thread;

import org.jetbrains.annotations.*;

import java.util.concurrent.*;

/**
 * An {@link ExecutorService} that executes submitted tasks using pooled grid threads.
 *
 * @author @java.author
 * @version @java.version
 */
public class GridThreadPoolExecutor extends ThreadPoolExecutor {
    /** Default core pool size (value is {@code 100}). */
    public static final int DFLT_CORE_POOL_SIZE = 100;

    /**
     * Creates a new service with default initial parameters.
     * Default values are:
     * <table class="doctable">
     * <tr>
     *      <th>Name</th>
     *      <th>Default Value</th>
     * </tr>
     * <tr>
     *      <td>Core Pool Size</td>
     *      <td>{@code 100} (see {@link #DFLT_CORE_POOL_SIZE}).</td>
     * </tr>
     * <tr>
     *      <td>Maximum Pool Size</td>
     *      <td>None, is it is not used for unbounded queues.</td>
     * </tr>
     * <tr>
     *      <td>Keep alive time</td>
     *      <td>No limit (see {@link Long#MAX_VALUE}).</td>
     * </tr>
     * <tr>
     *      <td>Blocking Queue (see {@link BlockingQueue}).</td>
     *      <td>Unbounded linked blocking queue (see {@link LinkedBlockingDeque}).</td>
     * </tr>
     * </table>
     */
    public GridThreadPoolExecutor() {
        this(
            DFLT_CORE_POOL_SIZE,
            DFLT_CORE_POOL_SIZE,
            0,
            new LinkedBlockingDeque<Runnable>(),
            new GridThreadFactory(null),
            null
        );
    }

    /**
     * Creates a new service with the given initial parameters.
     *
     * @param corePoolSize The number of threads to keep in the pool, even if they are idle.
     * @param maxPoolSize The maximum number of threads to allow in the pool.
     * @param keepAliveTime When the number of threads is greater than the core, this is the maximum time
     *      that excess idle threads will wait for new tasks before terminating.
     * @param workQueue The queue to use for holding tasks before they are executed. This queue will hold only
     *      runnable tasks submitted by the {@link #execute(Runnable)} method.
     */
    public GridThreadPoolExecutor(
        int corePoolSize,
        int maxPoolSize,
        long keepAliveTime,
        BlockingQueue<Runnable> workQueue) {
        this(
            corePoolSize,
            maxPoolSize,
            keepAliveTime,
            workQueue,
            new GridThreadFactory(null),
            null
        );
    }

    /**
     * Creates a new service with the given initial parameters.
     *
     * @param corePoolSize The number of threads to keep in the pool, even if they are idle.
     * @param maxPoolSize The maximum number of threads to allow in the pool.
     * @param keepAliveTime When the number of threads is greater than the core, this is the maximum time
     *      that excess idle threads will wait for new tasks before terminating.
     * @param workQ The queue to use for holding tasks before they are executed. This queue will hold only the
     *      runnable tasks submitted by the {@link #execute(Runnable)} method.
     * @param hnd Optional handler to use when execution is blocked because the thread bounds and queue
     *      capacities are reached. If {@code null} then {@code AbortPolicy}
     *      handler is used by default.
     */
    public GridThreadPoolExecutor(
        int corePoolSize,
        int maxPoolSize,
        long keepAliveTime,
        BlockingQueue<Runnable> workQ,
        RejectedExecutionHandler hnd) {
        this(
            corePoolSize,
            maxPoolSize,
            keepAliveTime,
            workQ,
            new GridThreadFactory(null),
            hnd
        );
    }

    /**
     * Creates a new service with default initial parameters.
     * Default values are:
     * <table class="doctable">
     * <tr>
     *      <th>Name</th>
     *      <th>Default Value</th>
     * </tr>
     * <tr>
     *      <td>Core Pool Size</td>
     *      <td>{@code 100} (see {@link #DFLT_CORE_POOL_SIZE}).</td>
     * </tr>
     * <tr>
     *      <td>Maximum Pool Size</td>
     *      <td>None, is it is not used for unbounded queues.</td>
     * </tr>
     * <tr>
     *      <td>Keep alive time</td>
     *      <td>No limit (see {@link Long#MAX_VALUE}).</td>
     * </tr>
     * <tr>
     *      <td>Blocking Queue (see {@link BlockingQueue}).</td>
     *      <td>Unbounded linked blocking queue (see {@link LinkedBlockingDeque}).</td>
     * </tr>
     * </table>
     *
     * @param gridName Name of the grid.
     */
    public GridThreadPoolExecutor(String gridName) {
        this(
            DFLT_CORE_POOL_SIZE,
            DFLT_CORE_POOL_SIZE,
            0,
            new LinkedBlockingDeque<Runnable>(),
            new GridThreadFactory(gridName),
            null
        );
    }

    /**
     * Creates a new service with the given initial parameters.
     *
     * @param gridName Name of the grid
     * @param corePoolSize The number of threads to keep in the pool, even if they are idle.
     * @param maxPoolSize The maximum number of threads to allow in the pool.
     * @param keepAliveTime When the number of threads is greater than the core, this is the maximum time
     *      that excess idle threads will wait for new tasks before terminating.
     * @param workQ The queue to use for holding tasks before they are executed. This queue will hold only
     *      runnable tasks submitted by the {@link #execute(Runnable)} method.
     */
    public GridThreadPoolExecutor(
        String gridName,
        int corePoolSize,
        int maxPoolSize,
        long keepAliveTime,
        BlockingQueue<Runnable> workQ) {
        super(
            corePoolSize,
            maxPoolSize,
            keepAliveTime,
            TimeUnit.MILLISECONDS,
            workQ,
            new GridThreadFactory(gridName)
        );
    }

    /**
     * Creates a new service with the given initial parameters.
     *
     * @param gridName Name of the grid.
     * @param corePoolSize The number of threads to keep in the pool, even if they are idle.
     * @param maxPoolSize The maximum number of threads to allow in the pool.
     * @param keepAliveTime When the number of threads is greater than the core, this is the maximum time
     *      that excess idle threads will wait for new tasks before terminating.
     * @param workQ The queue to use for holding tasks before they are executed. This queue will hold only the
     *      runnable tasks submitted by the {@link #execute(Runnable)} method.
     * @param hnd Optional handler to use when execution is blocked because the thread bounds and queue
     *      capacities are reached. If {@code null} then {@code AbortPolicy}
     *      handler is used by default.
     */
    public GridThreadPoolExecutor(
        String gridName,
        int corePoolSize,
        int maxPoolSize,
        long keepAliveTime,
        BlockingQueue<Runnable> workQ,
        RejectedExecutionHandler hnd) {
        this(
            corePoolSize,
            maxPoolSize,
            keepAliveTime,
            workQ,
            new GridThreadFactory(gridName),
            hnd
        );
    }

    /**
     * Creates a new service with the given initial parameters.
     *
     * @param corePoolSize The number of threads to keep in the pool, even if they are idle.
     * @param maxPoolSize The maximum number of threads to allow in the pool.
     * @param keepAliveTime When the number of threads is greater than the core, this is the maximum time
     *      that excess idle threads will wait for new tasks before terminating.
     * @param workQ The queue to use for holding tasks before they are executed. This queue will hold only the
     *      runnable tasks submitted by the {@link #execute(Runnable)} method.
     * @param threadFactory Thread factory.
     * @param hnd Optional handler to use when execution is blocked because the thread bounds and queue
     *      capacities are reached. If {@code null} then {@code AbortPolicy}
     *      handler is used by default.
     */
    public GridThreadPoolExecutor(
        int corePoolSize,
        int maxPoolSize,
        long keepAliveTime,
        BlockingQueue<Runnable> workQ,
        ThreadFactory threadFactory,
        @Nullable RejectedExecutionHandler hnd) {
        super(
            corePoolSize,
            maxPoolSize,
            keepAliveTime,
            TimeUnit.MILLISECONDS,
            workQ,
            threadFactory,
            hnd == null ? new AbortPolicy() : hnd
        );
    }
}
