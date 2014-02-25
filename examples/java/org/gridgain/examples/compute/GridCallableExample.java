// @java.file.header

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.examples.compute;

import org.gridgain.grid.*;
import org.gridgain.grid.compute.*;
import org.gridgain.grid.lang.*;

import java.util.*;

/**
 * Demonstrates a simple use of GridGain grid with reduce closure.
 * <p>
 * String "Hello Grid Enabled World!" is split into words and is passed as an argument to
 * {@link GridCompute#call(Collection)} method.
 * This method also takes as an argument a job factory instance, which is responsible for creating
 * jobs. Those jobs are then distributed among the running nodes. The {@code GridReducer} instance
 * then receives all job results and sums them up. The result of the execution is the number of
 * non-white-space characters in the initial sentence. All nodes should also print out the words
 * that were processed on them.
 * <p>
 * <h1 class="header">Starting Remote Nodes</h1>
 * To try this example you should (but don't have to) start remote grid instances.
 * You can start as many as you like by executing the following script:
 * <pre class="snippet">{GRIDGAIN_HOME}/bin/ggstart.{bat|sh} examples/config/example-default.xml</pre>
 * Once remote instances are started, you can execute this example from
 * Eclipse, IntelliJ IDEA, or NetBeans (and any other Java IDE) by simply hitting run
 * button. You will see that all nodes discover each other and
 * some of the nodes will participate in task execution (check node
 * output).
 *
 * @author @java.author
 * @version @java.version
 */
public class GridCallableExample {
    /**
     * Execute {@code HelloWorld} example with job factory and reducer.
     *
     * @param args Command line arguments, none required but if provided
     *      first one should point to the Spring XML configuration file. See
     *      {@code "examples/config/"} for configuration file examples.
     * @throws GridException If example execution failed.
     */
    public static void main(String[] args) throws GridException {
        try (Grid g = GridGain.start("examples/config/example-default.xml")) {
            Collection<GridCallable<Integer>> calls = new ArrayList<>();

            // Iterate through all words in the sentence and create callable jobs.
            for (final String word : "Count characters using callable".split(" ")) {
                calls.add(new GridCallable<Integer>() {
                    @Override public Integer call() throws Exception {
                        System.out.println(">>>");
                        System.out.println(">>> Printing '" + word + "' on this node from grid job.");

                        return word.length();
                    }
                });
            }

            // Execute collection of callables on the grid.
            Collection<Integer> res = g.compute().call(calls).get();

            int sum = 0;

            // Add up individual word lengths received from remote nodes.
            for (Integer len : res)
                sum += len;

            System.out.println();
            System.out.println(">>> Total number of characters in the phrase is '" + sum + "'.");
            System.out.println(">>> Check all nodes for output (this node is also part of the grid).");
        }
    }
}