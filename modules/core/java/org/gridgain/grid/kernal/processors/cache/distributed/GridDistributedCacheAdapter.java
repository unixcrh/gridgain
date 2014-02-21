// @java.file.header

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache.distributed;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.util.*;

/**
 * Distributed cache implementation.
 *
 * @author @java.author
 * @version @java.version
 */
public abstract class GridDistributedCacheAdapter<K, V> extends GridCacheAdapter<K, V> {
    /**
     * Empty constructor required by {@link Externalizable}.
     */
    protected GridDistributedCacheAdapter() {
        // No-op.
    }

    /**
     * @param ctx Cache registry.
     * @param startSize Start size.
     */
    protected GridDistributedCacheAdapter(GridCacheContext<K, V> ctx, int startSize) {
        super(ctx, startSize);
    }

    /**
     * @param ctx Cache context.
     * @param map Cache map.
     */
    protected GridDistributedCacheAdapter(GridCacheContext<K, V> ctx, GridCacheConcurrentMap<K, V> map) {
        super(ctx, map);
    }

    /** {@inheritDoc} */
    @Override public GridFuture<Boolean> txLockAsync(
        Collection<? extends K> keys,
        long timeout,
        GridCacheTxLocalEx<K, V> tx,
        boolean isRead,
        boolean retval,
        GridCacheTxIsolation isolation,
        boolean isInvalidate,
        GridPredicate<GridCacheEntry<K, V>>[] filter
    ) {
        assert tx != null;

        return lockAllAsync(keys, timeout, tx, isInvalidate, isRead, retval, isolation, filter);
    }

    /** {@inheritDoc} */
    @Override public GridFuture<Boolean> lockAllAsync(Collection<? extends K> keys, long timeout,
        GridPredicate<GridCacheEntry<K, V>>... filter) {
        GridCacheTxLocalEx<K, V> tx = ctx.tm().userTxx();

        // Return value flag is true because we choose to bring values for explicit locks.
        return lockAllAsync(keys, timeout, tx, false, false, /*retval*/true, null, filter);
    }

    /**
     * @param keys Keys to lock.
     * @param timeout Timeout.
     * @param tx Transaction
     * @param isInvalidate Invalidation flag.
     * @param isRead Indicates whether value is read or written.
     * @param retval Flag to return value.
     * @param isolation Transaction isolation.
     * @param filter Optional filter.
     * @return Future for locks.
     */
    protected abstract GridFuture<Boolean> lockAllAsync(Collection<? extends K> keys, long timeout,
        @Nullable GridCacheTxLocalEx<K, V> tx, boolean isInvalidate, boolean isRead, boolean retval,
        @Nullable GridCacheTxIsolation isolation, GridPredicate<GridCacheEntry<K, V>>[] filter);

    /** {@inheritDoc} */
    @Override public String toString() {
        return S.toString(GridDistributedCacheAdapter.class, this, "super", super.toString());
    }
}