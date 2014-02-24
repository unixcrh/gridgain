// @java.file.header

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.examples.basic.datagrid;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.product.*;
import org.gridgain.grid.util.lang.*;

import java.util.concurrent.*;

import static org.gridgain.grid.product.GridProductEdition.*;

/**
 * This example demonstrates some of the cache rich API capabilities.
 * You can execute this example with or without remote nodes.
 * <p>
 * Remote nodes should always be started with configuration file which includes
 * cache: {@code 'ggstart.sh examples/config/example-cache.xml'}.
 *
 * @author @java.author
 * @version @java.version
 */
@GridOnlyAvailableIn(DATA_GRID)
public class CacheApiExample {
    /** Cache name. */
    private static final String CACHE_NAME = "partitioned";
    //private static final String CACHE_NAME = "replicated";
    //private static final String CACHE_NAME = "local";

    /**
     * Put data to cache and then query it.
     *
     * @param args Command line arguments, none required.
     * @throws GridException If example execution failed.
     */
    public static void main(String[] args) throws GridException {
        try (Grid g = GridGain.start("examples/config/example-cache.xml")) {
            // Demonstrate atomic map operations.
            atomicMapOperations();

            // Demonstrate various ways to iterate over locally cached values.
            localIterators();
        }
    }

    /**
     * Demonstrates cache operations similar to {@link ConcurrentMap} API. Note that
     * cache API is a lot richer than the JDK {@link ConcurrentMap}.
     *
     * @throws GridException If failed.
     */
    private static void atomicMapOperations() throws GridException {
        System.out.println();
        System.out.println(">>> Cache atomic map operation examples.");

        GridCache<Integer, String> cache = GridGain.grid().cache(CACHE_NAME);

        // Put and return previous value.
        String v = cache.put(1, "1");
        assert v == null;

        // Put and do not return previous value. All methods ending with 'x' behave this way.
        // Performs better when previous value is not needed.
        cache.putx(2, "2");

        // Put asynchronously (every cache operation has async counterpart).
        GridFuture<String> fut = cache.putAsync(3, "3");

        // Asynchronously wait for result.
        fut.listenAsync(new GridInClosure<GridFuture<String>>() {
            @Override public void apply(GridFuture<String> fut) {
                try {
                    System.out.println("Put operation completed [previous-value=" + fut.get() + ']');
                }
                catch (GridException e) {
                    throw new GridClosureException(e);
                }
            }
        });

        // Put-if-absent.
        boolean b1 = cache.putxIfAbsent(4, "4");
        boolean b2 = cache.putxIfAbsent(4, "44");
        assert b1 && !b2;


        // Put-with-predicate, will succeed if predicate evaluates to true.
        cache.putx(5, "5");
        cache.putx(5, "55", new GridPredicate<GridCacheEntry<Integer, String>>() {
            @Override public boolean apply(GridCacheEntry<Integer, String> e) {
                return "5".equals(e.peek()); // Update only if previous value is "5".
            }
        });

        // Transform - assign new value based on previous value.
        cache.putx(6, "6");
        cache.transform(6, new GridClosure<String, String>() {
            @Override public String apply(String v) {
                return v + "6"; // Set new value based on previous value.
            }
        });

        // Replace.
        cache.putx(7, "7");
        b1 = cache.replace(7, "7", "77");
        b2 = cache.replace(7, "7", "777");
        assert b1 & !b2;
    }

    /**
     * Demonstrates various iteration methods over locally cached values.
     */
    private static void localIterators() {
        System.out.println();
        System.out.println(">>> Local iterator examples.");

        GridCache<Integer, String> cache = GridGain.grid().cache(CACHE_NAME);

        // Iterate over whole cache.
        for (GridCacheEntry<Integer, String> e : cache)
            System.out.println("Basic cache iteration [key=" + e.getKey() + ", val=" + e.getValue() + ']');

        // Iterate over cache projection for all keys below 5.
        for (GridCacheEntry<Integer, String> e : cache.projection(new GridPredicate<GridCacheEntry<Integer, String>>() {
            @Override public boolean apply(GridCacheEntry<Integer, String> e) {
                return e.getKey() < 5;
            }
        })) {
            System.out.println("Cache projection iteration [key=" + e.getKey() + ", val=" + e.getValue() + ']');
        }

        // Iterate over each element using 'forEach' construct.
        cache.forEach(new GridInClosure<GridCacheEntry<Integer, String>>() {
            @Override public void apply(GridCacheEntry<Integer, String> e) {
                System.out.println("forEach iteration [key=" + e.getKey() + ", val=" + e.getValue() + ']');
            }
        });

        // Search cache for element with value "1" using 'forAll' construct.
        cache.forAll(new GridPredicate<GridCacheEntry<Integer, String>>() {
            @Override public boolean apply(GridCacheEntry<Integer, String> e) {
                String v = e.peek();

                if ("1".equals(v)) {
                    System.out.println("Found cache value '1' using forEach iteration.");

                    return false; // Stop iteration.
                }

                return true; // Continue iteration.
            }
        });
    }
}