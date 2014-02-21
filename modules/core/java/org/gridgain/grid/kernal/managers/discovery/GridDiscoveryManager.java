// @java.file.header

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.managers.discovery;

import org.gridgain.grid.*;
import org.gridgain.grid.events.*;
import org.gridgain.grid.kernal.*;
import org.gridgain.grid.kernal.managers.*;
import org.gridgain.grid.kernal.managers.communication.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.kernal.processors.jobmetrics.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.product.*;
import org.gridgain.grid.segmentation.*;
import org.gridgain.grid.spi.*;
import org.gridgain.grid.spi.discovery.*;
import org.gridgain.grid.spi.metrics.*;
import org.gridgain.grid.thread.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.lang.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.grid.util.worker.*;
import org.jetbrains.annotations.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;

import static java.util.concurrent.TimeUnit.*;
import static org.gridgain.grid.events.GridEventType.*;
import static org.gridgain.grid.kernal.GridNodeAttributes.*;
import static org.gridgain.grid.segmentation.GridSegmentationPolicy.*;

/**
 * Discovery SPI manager.
 *
 * @author @java.author
 * @version @java.version
 */
public class GridDiscoveryManager extends GridManagerAdapter<GridDiscoverySpi> {
    /** Fake key for {@code null}-named caches. Used inside {@link DiscoCache}. */
    private static final String NULL_CACHE_NAME = UUID.randomUUID().toString();

    /** Dynamically proxy-enabled methods for shadow. */
    private static final String[] SHADOW_PROXY_METHODS = new String[] {
        "id", "attribute", "attributes", "order"
    };

    /** */
    private static final String PREFIX = "Topology snapshot";

    /** Discovery cached history size. */
    protected static final int DISCOVERY_HISTORY_SIZE = 100;

    /** Predicate filtering out daemon nodes. */
    private static final GridPredicate<GridNode> daemonFilter = new P1<GridNode>() {
        @Override public boolean apply(GridNode n) {
            return !n.isDaemon();
        }
    };

    /** Disco history entries comparator. */
    private static final Comparator<Map.Entry<Long, DiscoCache>> histCmp =
        new Comparator<Map.Entry<Long, DiscoCache>>() {
            @Override public int compare(Map.Entry<Long, DiscoCache> o1, Map.Entry<Long, DiscoCache> o2) {
                return o1.getKey().compareTo(o2.getKey());
            }
        };

    /** Alive filter. */
    private final GridPredicate<GridNode> aliveFilter = new P1<GridNode>() {
        @Override public boolean apply(GridNode n) {
            return node(n.id()) != null;
        }
    };

    /** Discovery event worker. */
    private final DiscoveryWorker discoWrk = new DiscoveryWorker();

    /** Discovery event worker thread. */
    private GridThread discoWrkThread;

    /** Network segment check worker. */
    private SegmentCheckWorker segChkWrk;

    /** Network segment check thread. */
    private GridThread segChkThread;

    /** Reconnect worker. */
    private ReconnectWorker reconWrk;

    /** Reconnect thread. */
    private GridThread reconThread;

    /** Last logged topology. */
    private final AtomicLong lastLoggedTop = new AtomicLong();

    /** Local node. */
    private GridNode locNode;

    /** Local node daemon flag. */
    private boolean isLocDaemon;

    /** {@code True} if resolvers were configured and network segment check is enabled. */
    private boolean hasRslvrs;

    /** Last segment check result. */
    private final AtomicBoolean lastSegChkRes = new AtomicBoolean(true);

    /** Discovery cache. */
    private final AtomicReference<DiscoCache> discoCache = new AtomicReference<>();

    /** Topology cache history. */
    private final GridBoundedConcurrentLinkedHashMap<Long, DiscoCache> discoCacheHist =
        new GridBoundedConcurrentLinkedHashMap<>(DISCOVERY_HISTORY_SIZE,
            DISCOVERY_HISTORY_SIZE, 0.7f, 1);

    /** Topology snapshots history. */
    private volatile Map<Long, Collection<GridNode>> topHist = new HashMap<>();

    /** Topology version. */
    private final GridAtomicLong topVer = new GridAtomicLong();

    /** Order supported flag. */
    private boolean discoOrdered;

    /** Topology snapshots history supported flag. */
    private boolean histSupported;

    /** Configured network segment check frequency. */
    private long segChkFreq;

    /** Local node join to topology event. */
    private GridDiscoveryEvent locJoinEvt;

    /** @param ctx Context. */
    public GridDiscoveryManager(GridKernalContext ctx) {
        super(ctx, ctx.config().getDiscoverySpi());
    }

    /**
     * Checks that node shadow has all methods used in proxy.
     */
    static {
        Method[] mtds = GridNodeShadow.class.getDeclaredMethods();

        for (String mtd : SHADOW_PROXY_METHODS) {
            boolean found = false;

            for (Method clsMtd : mtds) {
                if (clsMtd.getName().equals(mtd)) {
                    found = true;

                    break;
                }
            }

            if (!found)
                throw new GridRuntimeException(GridNodeShadow.class.getSimpleName() + " class does not implement " +
                    "proxy method (were methods renamed?): " + mtd);
        }
    }

    /**
     * Sets local node attributes into discovery SPI.
     *
     * @param attrs Attributes to set.
     * @param ver Version.
     */
    public void setNodeAttributes(Map<String, Object> attrs, GridProductVersion ver) {
        getSpi().setNodeAttributes(attrs, ver);
    }

    /** {@inheritDoc} */
    @Override public void start() throws GridException {
        discoOrdered = discoOrdered();

        histSupported = historySupported();

        isLocDaemon = ctx.isDaemon();

        hasRslvrs = !F.isEmpty(ctx.config().getSegmentationResolvers());

        segChkFreq = ctx.config().getSegmentCheckFrequency();

        if (hasRslvrs) {
            if (segChkFreq < 0)
                throw new GridException("Segment check frequency cannot be negative: " + segChkFreq);

            if (segChkFreq > 0 && segChkFreq < 2000)
                U.warn(log, "Configuration parameter 'segmentCheckFrequency' is too low " +
                    "(at least 2000 ms recommended): " + segChkFreq);

            checkSegmentOnStart();
        }

        getSpi().setMetricsProvider(new GridDiscoveryMetricsProvider() {
            /** */
            private final long startTime = U.currentTimeMillis();

            /** {@inheritDoc} */
            @Override public GridNodeMetrics getMetrics() {
                GridJobMetrics jm = ctx.jobMetric().getJobMetrics();

                GridDiscoveryMetricsAdapter nm = new GridDiscoveryMetricsAdapter();

                nm.setLastUpdateTime(U.currentTimeMillis());

                // Job metrics.
                nm.setMaximumActiveJobs(jm.getMaximumActiveJobs());
                nm.setCurrentActiveJobs(jm.getCurrentActiveJobs());
                nm.setAverageActiveJobs(jm.getAverageActiveJobs());
                nm.setMaximumWaitingJobs(jm.getMaximumWaitingJobs());
                nm.setCurrentWaitingJobs(jm.getCurrentWaitingJobs());
                nm.setAverageWaitingJobs(jm.getAverageWaitingJobs());
                nm.setMaximumRejectedJobs(jm.getMaximumRejectedJobs());
                nm.setCurrentRejectedJobs(jm.getCurrentRejectedJobs());
                nm.setAverageRejectedJobs(jm.getAverageRejectedJobs());
                nm.setMaximumCancelledJobs(jm.getMaximumCancelledJobs());
                nm.setCurrentCancelledJobs(jm.getCurrentCancelledJobs());
                nm.setAverageCancelledJobs(jm.getAverageCancelledJobs());
                nm.setTotalRejectedJobs(jm.getTotalRejectedJobs());
                nm.setTotalCancelledJobs(jm.getTotalCancelledJobs());
                nm.setTotalExecutedJobs(jm.getTotalExecutedJobs());
                nm.setMaximumJobWaitTime(jm.getMaximumJobWaitTime());
                nm.setCurrentJobWaitTime(jm.getCurrentJobWaitTime());
                nm.setAverageJobWaitTime(jm.getAverageJobWaitTime());
                nm.setMaximumJobExecuteTime(jm.getMaximumJobExecuteTime());
                nm.setCurrentJobExecuteTime(jm.getCurrentJobExecuteTime());
                nm.setAverageJobExecuteTime(jm.getAverageJobExecuteTime());
                nm.setCurrentIdleTime(jm.getCurrentIdleTime());
                nm.setTotalIdleTime(jm.getTotalIdleTime());
                nm.setAverageCpuLoad(jm.getAverageCpuLoad());

                // Job metrics.
                nm.setTotalExecutedTasks(ctx.task().getTotalExecutedTasks());

                GridLocalMetrics lm = ctx.localMetric().metrics();

                // VM metrics.
                nm.setAvailableProcessors(lm.getAvailableProcessors());
                nm.setCurrentCpuLoad(lm.getCurrentCpuLoad());
                nm.setCurrentGcCpuLoad(lm.getCurrentGcCpuLoad());
                nm.setHeapMemoryInitialized(lm.getHeapMemoryInitialized());
                nm.setHeapMemoryUsed(lm.getHeapMemoryUsed());
                nm.setHeapMemoryCommitted(lm.getHeapMemoryCommitted());
                nm.setHeapMemoryMaximum(lm.getHeapMemoryMaximum());
                nm.setNonHeapMemoryInitialized(lm.getNonHeapMemoryInitialized());
                nm.setNonHeapMemoryUsed(lm.getNonHeapMemoryUsed());
                nm.setNonHeapMemoryCommitted(lm.getNonHeapMemoryCommitted());
                nm.setNonHeapMemoryMaximum(lm.getNonHeapMemoryMaximum());
                nm.setUpTime(lm.getUptime());
                nm.setStartTime(lm.getStartTime());
                nm.setNodeStartTime(startTime);
                nm.setCurrentThreadCount(lm.getThreadCount());
                nm.setMaximumThreadCount(lm.getPeakThreadCount());
                nm.setTotalStartedThreadCount(lm.getTotalStartedThreadCount());
                nm.setCurrentDaemonThreadCount(lm.getDaemonThreadCount());
                nm.setFileSystemFreeSpace(lm.getFileSystemFreeSpace());
                nm.setFileSystemTotalSpace(lm.getFileSystemTotalSpace());
                nm.setFileSystemUsableSpace(lm.getFileSystemUsableSpace());

                // Data metrics.
                nm.setLastDataVersion(ctx.cache().lastDataVersion());

                GridIoManager io = ctx.io();

                // IO metrics.
                nm.setSentMessagesCount(io.getSentMessagesCount());
                nm.setSentBytesCount(io.getSentBytesCount());
                nm.setReceivedMessagesCount(io.getReceivedMessagesCount());
                nm.setReceivedBytesCount(io.getReceivedBytesCount());

                return nm;
            }
        });

        // Start reconnect worker first.
        // We should always start it, even if we have no resolvers.
        if (ctx.config().getSegmentationPolicy() == RECONNECT) {
            reconWrk = new ReconnectWorker();

            reconThread = new GridThread(reconWrk);

            reconThread.start();
        }

        getSpi().setListener(new GridDiscoverySpiListener() {
            @Override public void onDiscovery(int type, long topVer, GridNode node, Collection<GridNode> topSnapshot,
                Map<Long, Collection<GridNode>> snapshots) {
                final GridNode locNode = localNode();

                if (snapshots != null)
                    topHist = snapshots;

                if (type == EVT_NODE_FAILED || type == EVT_NODE_LEFT)
                    for (DiscoCache c : discoCacheHist.values())
                        c.updateAlives(node);

                // Put topology snapshot into discovery history.
                if (type != EVT_NODE_METRICS_UPDATED) {
                    DiscoCache cache = new DiscoCache(locNode, F.view(topSnapshot, F.remoteNodes(locNode.id())));

                    discoCacheHist.put(topVer, cache);
                    discoCache.set(cache);
                }

                // If this is a local join event, just save it and do not notify listeners.
                if (type == EVT_NODE_JOINED && node.id().equals(locNode.id())) {
                    GridDiscoveryEvent discoEvt = new GridDiscoveryEvent();

                    discoEvt.nodeId(ctx.localNodeId());
                    discoEvt.eventNodeId(node.id());
                    discoEvt.type(EVT_NODE_JOINED);
                    discoEvt.shadow(new GridDiscoveryNodeShadowAdapter(node));

                    discoEvt.topologySnapshot(topVer, new ArrayList<>(
                        F.viewReadOnly(topSnapshot, new C1<GridNode, GridNodeShadow>() {
                            @Override public GridNodeShadow apply(GridNode e) {
                                return new GridDiscoveryNodeShadowAdapter(e);
                            }
                        }, daemonFilter)));

                    locJoinEvt = discoEvt;

                    return;
                }

                if (topVer > 0 && (type == EVT_NODE_JOINED || type == EVT_NODE_FAILED || type == EVT_NODE_LEFT ||
                    type == EVT_NODE_RECONNECTED)) {
                    boolean set = GridDiscoveryManager.this.topVer.setIfGreater(topVer);

                    assert set : "Topology version has not been updated [this.topVer=" +
                        GridDiscoveryManager.this.topVer + ", topVer=" + topVer + ", node=" + node +
                        ", evt=" + U.gridEventName(type) + ']';
                }

                discoWrk.addEvent(type, topVer, node, topSnapshot);
            }
        });

        getSpi().setDataExchange(new GridDiscoverySpiDataExchange() {
            @Override public List<Object> collect(UUID nodeId) {
                assert nodeId != null;

                List<Object> data = new LinkedList<>();

                for (GridComponent comp : ctx.components())
                    data.add(comp.collectDiscoveryData(nodeId));

                return data;
            }

            @Override public void onExchange(List<Object> data) {
                assert data != null;

                Iterator<Object> it = data.iterator();

                for (GridComponent comp : ctx.components()) {
                    assert it.hasNext();

                    comp.onDiscoveryDataReceived(it.next());
                }
            }
        });

        startSpi();

        // Start segment check worker only if frequency is greater than 0.
        if (hasRslvrs && segChkFreq > 0) {
            segChkWrk = new SegmentCheckWorker();

            segChkThread = new GridThread(segChkWrk);

            segChkThread.start();
        }

        checkAttributes(discoCache().remoteNodes());

        locNode = getSpi().getLocalNode();

        topVer.setIfGreater(locNode.order());

        // Start discovery worker.
        discoWrkThread = new GridThread(ctx.gridName(), "disco-event-worker", discoWrk);

        discoWrkThread.start();

        ctx.versionConverter().onStart(discoCache().remoteNodes());

        if (log.isDebugEnabled())
            log.debug(startInfo());
    }

    /** @return {@code True} if ordering is supported. */
    private boolean discoOrdered() {
        GridDiscoverySpiOrderSupport ann = U.getAnnotation(ctx.config().getDiscoverySpi().getClass(),
            GridDiscoverySpiOrderSupport.class);

        return ann != null && ann.value();
    }

    /** @return {@code True} if topology snapshots history is supported. */
    private boolean historySupported() {
        GridDiscoverySpiHistorySupport ann = U.getAnnotation(ctx.config().getDiscoverySpi().getClass(),
            GridDiscoverySpiHistorySupport.class);

        return ann != null && ann.value();
    }

    /**
     * Checks segment on start waiting for correct segment if necessary.
     *
     * @throws GridException If check failed.
     */
    private void checkSegmentOnStart() throws GridException {
        assert hasRslvrs;

        if (log.isDebugEnabled())
            log.debug("Starting network segment check.");

        while (true) {
            if (ctx.segmentation().isValidSegment())
                break;

            if (ctx.config().isWaitForSegmentOnStart()) {
                LT.warn(log, null, "Failed to check network segment (retrying every 2000 ms).");

                // Wait and check again.
                U.sleep(2000);
            }
            else
                throw new GridException("Failed to check network segment.");
        }

        if (log.isDebugEnabled())
            log.debug("Finished network segment check successfully.");
    }

    /**
     * Checks whether edition and build version of the local node are consistent with remote nodes.
     *
     * @param nodes List of remote nodes to check attributes on.
     * @throws GridException In case of error.
     */
    private void checkAttributes(Iterable<GridNode> nodes) throws GridException {
        GridNode locNode = getSpi().getLocalNode();

        assert locNode != null;

        // Fetch local node attributes once.
        String locPreferIpV4 = locNode.attribute("java.net.preferIPv4Stack");

        Object locMode = locNode.attribute(ATTR_DEPLOYMENT_MODE);

        boolean locP2pEnabled = locNode.attribute(ATTR_PEER_CLASSLOADING);

        List<String> locLibs = locNode.attribute(ATTR_LIBRARIES);

        Byte locDataCenterId = locNode.attribute(ATTR_DATA_CENTER_ID);

        boolean warned = false;

        for (GridNode n : nodes) {
            String rmtPreferIpV4 = n.attribute("java.net.preferIPv4Stack");

            if (!F.eq(rmtPreferIpV4, locPreferIpV4)) {
                if (!warned)
                    U.warn(log, "Local node's value of 'java.net.preferIPv4Stack' " +
                        "system property differs from remote node's " +
                        "(all nodes in topology should have identical value) " +
                        "[locPreferIpV4=" + locPreferIpV4 + ", rmtPreferIpV4=" + rmtPreferIpV4 +
                        ", locId8=" + U.id8(locNode.id()) + ", rmtId8=" + U.id8(n.id()) +
                        ", rmtAddrs=" + U.addressesAsString(n) + ']',
                        "Local and remote 'java.net.preferIPv4Stack' system properties do not match.");

                warned = true;
            }

            // Daemon nodes are allowed to have any deployment they need.
            // Skip data center ID check for daemon nodes.
            if (!isLocDaemon && !n.isDaemon()) {
                Byte rmtDataCenterId = n.attribute(ATTR_DATA_CENTER_ID);

                if (!F.eq(locDataCenterId, rmtDataCenterId))
                    throw new GridException("Remote node has data center ID different from local " +
                        "[locDataCenterId=" + locDataCenterId + ", rmtDataCenterId=" + rmtDataCenterId + ']');

                Object rmtMode = n.attribute(ATTR_DEPLOYMENT_MODE);

                if (!locMode.equals(rmtMode))
                    throw new GridException("Remote node has deployment mode different from local " +
                        "[locId8=" + U.id8(locNode.id()) + ", locMode=" + locMode +
                        ", rmtId8=" + U.id8(n.id()) + ", rmtMode=" + rmtMode +
                        ", rmtAddrs=" + U.addressesAsString(n) + ']');

                boolean rmtP2pEnabled = n.attribute(ATTR_PEER_CLASSLOADING);

                if (locP2pEnabled != rmtP2pEnabled)
                    throw new GridException("Remote node has peer class loading enabled flag different from local " +
                        "[locId8=" + U.id8(locNode.id()) + ", locPeerClassLoading=" + locP2pEnabled +
                        ", rmtId8=" + U.id8(n.id()) + ", rmtPeerClassLoading=" + rmtP2pEnabled +
                        ", rmtAddrs=" + U.addressesAsString(n) + ']');
            }

            List<String> rmtLibs = n.attribute(ATTR_LIBRARIES);

            List<GridBiTuple<String, String>> diffs = GridLibraryConsistencyCheck.check(locLibs, rmtLibs);

            if (!diffs.isEmpty()) {
                if (log.isQuiet()) {
                    U.quiet(true, "Local node's library list differs from remote node's");

                    for (GridBiTuple<String, String> diff : diffs)
                        U.quiet(true, "<" + diff.get1() + "> vs. <" + diff.get2() + '>');

                    U.quiet(true, "");
                }

                StringBuilder sb = new StringBuilder("\n" +
                    ">>> Local node's library list differs from remote node's\n" +
                    ">>> (this may cause class incompatibilities, ignore if on purpose)\n" +
                    ">>> locId8=" + U.id8(locNode.id()) + " vs. rmtId8=" + U.id8(n.id()) + "\n");

                for (GridBiTuple<String, String> diff : diffs)
                    sb.append(">>> <").append(diff.get1()).append("> vs. <").append(diff.get2()).append(">\n");

                log.warning(sb.toString());
            }
        }

        if (log.isDebugEnabled())
            log.debug("Finished node attributes consistency check.");
    }

    /**
     * @param nodes Nodes.
     * @return Total CPUs.
     */
    private static int cpus(Collection<GridNode> nodes) {
        Collection<String> macSet = new HashSet<>(nodes.size(), 1.0f);

        int cpus = 0;

        for (GridNode n : nodes) {
            String macs = n.attribute(ATTR_MACS);

            if (macSet.add(macs))
                cpus += n.metrics().getTotalCpus();
        }

        return cpus;
    }

    /**
     * Prints the latest topology info into log taking into account logging/verbosity settings.
     */
    public void ackTopology() {
        ackTopology(topVer.get(), false);
    }

    /**
     * Logs grid size for license compliance.
     *
     * @param topVer Topology version.
     * @param throttle Suppress printing if this topology was already printed.
     */
    private void ackTopology(long topVer, boolean throttle) {
        assert !isLocDaemon;

        DiscoCache discoCache = discoCache();

        Collection<GridNode> rmtNodes = discoCache.remoteNodes();

        GridNode locNode = discoCache.localNode();

        Collection<GridNode> allNodes = discoCache.allNodes();

        long hash = topologyHash(allNodes);

        // Prevent ack-ing topology for the same topology.
        // Can happen only during node startup.
        if (throttle && lastLoggedTop.getAndSet(hash) == hash)
            return;

        int totalCpus = cpus(allNodes);

        double heap = U.heapSize(allNodes, 2);

        if (log.isQuiet())
            U.quiet(false, topologySnapshotMessage(rmtNodes.size(), totalCpus, heap));

        if (log.isDebugEnabled()) {
            String dbg = "";

            dbg += U.nl() + U.nl() +
                ">>> +----------------+" + U.nl() +
                ">>> " + PREFIX + "." + U.nl() +
                ">>> +----------------+" + U.nl() +
                ">>> Grid name: " + (ctx.gridName() == null ? "default" : ctx.gridName()) + U.nl() +
                ">>> Number of nodes: " + (rmtNodes.size() + 1) + U.nl() +
                (discoOrdered ? ">>> Topology version: " + topVer + U.nl() : "") +
                ">>> Topology hash: 0x" + Long.toHexString(hash).toUpperCase() + U.nl();

            dbg += ">>> Local: " +
                locNode.id().toString().toUpperCase() + ", " +
                U.addressesAsString(locNode) + ", " +
                locNode.order() + ", " +
                locNode.attribute("os.name") + ' ' +
                locNode.attribute("os.arch") + ' ' +
                locNode.attribute("os.version") + ", " +
                System.getProperty("user.name") + ", " +
                locNode.attribute("java.runtime.name") + ' ' +
                locNode.attribute("java.runtime.version") + U.nl();

            for (GridNode node : rmtNodes)
                dbg += ">>> Remote: " +
                    node.id().toString().toUpperCase() + ", " +
                    U.addressesAsString(node) + ", " +
                    node.order() + ", " +
                    node.attribute("os.name") + ' ' +
                    node.attribute("os.arch") + ' ' +
                    node.attribute("os.version") + ", " +
                    node.attribute(ATTR_USER_NAME) + ", " +
                    node.attribute("java.runtime.name") + ' ' +
                    node.attribute("java.runtime.version") + U.nl();

            dbg += ">>> Total number of CPUs: " + totalCpus + U.nl();
            dbg += ">>> Total heap size: " + heap + "GB" + U.nl();

            log.debug(dbg);
        }
        else if (log.isInfoEnabled())
            log.info(topologySnapshotMessage(rmtNodes.size(), totalCpus, heap));
    }

    /**
     * @param rmtNodesNum Remote nodes number.
     * @param totalCpus Total cpu number.
     * @param heap Heap size.
     * @return Topology snapshot message.
     */
    private String topologySnapshotMessage(int rmtNodesNum, int totalCpus, double heap) {
        return PREFIX + " [" +
            (discoOrdered ? "ver=" + topVer + ", " : "") +
            "nodes=" + (rmtNodesNum + 1) +
            ", CPUs=" + totalCpus +
            ", heap=" + heap + "GB" +
            ']';
    }

    /** {@inheritDoc} */
    @Override public void onKernalStop0(boolean cancel) {
        // Stop segment check worker.
        if (segChkWrk != null) {
            segChkWrk.cancel();

            U.join(segChkThread, log);
        }

        // Stop reconnect worker.
        if (reconWrk != null) {
            reconWrk.cancel();

            U.join(reconThread, log);
        }
    }

    /** {@inheritDoc} */
    @Override public void stop(boolean cancel) throws GridException {
        // Stop receiving notifications.
        getSpi().setListener(null);

        // Stop discovery worker.
        discoWrk.cancel();

        U.join(discoWrkThread, log);

        // Stop SPI itself.
        stopSpi();

        if (log.isDebugEnabled())
            log.debug(stopInfo());
    }

    /**
     * Gets node shadow.
     *
     * @param node Node.
     * @return Node's shadow.
     */
    public GridNodeShadow shadow(GridNode node) {
        return new GridDiscoveryNodeShadowAdapter(node);
    }

    /**
     * @param nodeIds Node IDs to check.
     * @return {@code True} if at least one ID belongs to an alive node.
     */
    public boolean aliveAny(@Nullable Collection<UUID> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty())
            return false;

        for (UUID id : nodeIds)
            if (alive(id))
                return true;

        return false;
    }

    /**
     * @param nodeIds Node IDs to check.
     * @return {@code True} if at least one ID belongs to an alive node.
     */
    public boolean aliveAll(@Nullable Collection<UUID> nodeIds) {
        if (nodeIds == null || nodeIds.isEmpty())
            return false;

        for (UUID id : nodeIds)
            if (!alive(id))
                return false;

        return true;
    }

    /**
     * @param nodeId Node ID.
     * @return {@code True} if node for given ID is alive.
     */
    public boolean alive(UUID nodeId) {
        assert nodeId != null;

        boolean alive = getSpi().getNode(nodeId) != null; // Go directly to SPI without checking disco cache.

        // Refresh disco cache if some node died.
        if (!alive) {
            while (true) {
                DiscoCache c = discoCache();

                if (c.node(nodeId) != null) {
                    if (discoCache.compareAndSet(c, null))
                        break;
                }
                else
                    break;
            }
        }

        return alive;
    }

    /**
     * @param node Node.
     * @return {@code True} if node is alive.
     */
    public boolean alive(GridNode node) {
        assert node != null;

        return alive(node.id());
    }

    /**
     * @param nodeId ID of the node.
     * @return {@code True} if ping succeeded.
     */
    public boolean pingNode(UUID nodeId) {
        assert nodeId != null;

        return getSpi().pingNode(nodeId);
    }

    /**
     * @param nodeId ID of the node.
     * @return Node for ID.
     */
    @Nullable public GridNode node(UUID nodeId) {
        assert nodeId != null;

        return discoCache().node(nodeId);
    }

    /**
     * @param nodes Nodes.
     * @return Alive nodes.
     */
    public Collection<GridNode> aliveNodes(Collection<? extends GridNode> nodes) {
        return F.view((Collection<GridNode>)nodes, aliveFilter);
    }

    /**
     * @param p Filters.
     * @return Collection of nodes for given filters.
     */
    public Collection<GridNode> nodes(GridPredicate<GridNode>... p) {
        return F.isEmpty(p) ? allNodes() : F.view(allNodes(), p);
    }

    /**
     * Gets collection of node for given node IDs and predicates.
     *
     * @param ids Ids to include.
     * @param p Filter for IDs.
     * @return Collection with all alive nodes for given IDs.
     */
    public Collection<GridNode> nodes(@Nullable Collection<UUID> ids, GridPredicate<UUID>... p) {
        return F.isEmpty(ids) ? Collections.<GridNode>emptyList() :
            F.view(
                F.viewReadOnly(ids, U.id2Node(ctx), p),
                F.notNull());
    }

    /**
     * Gets topology hash for given set of nodes.
     *
     * @param nodes Subset of grid nodes for hashing.
     * @return Hash for given topology.
     */
    public long topologyHash(Iterable<? extends GridNode> nodes) {
        assert nodes != null;

        Iterator<? extends GridNode> iter = nodes.iterator();

        if (!iter.hasNext())
            return 0; // Special case.

        List<String> uids = new ArrayList<>();

        for (GridNode node : nodes)
            uids.add(node.id().toString());

        Collections.sort(uids);

        CRC32 hash = new CRC32();

        for (String uuid : uids)
            hash.update(uuid.getBytes());

        return hash.getValue();
    }

    /**
     * Gets future that will be completed when current topology version becomes greater or equal to argument passed.
     *
     * @param awaitVer Topology version to await.
     * @return Future.
     */
    public GridFuture<Long> topologyFuture(final long awaitVer) {
        long topVer = topologyVersion();

        if (topVer >= awaitVer)
            return new GridFinishedFuture<>(ctx, topVer);

        DiscoTopologyFuture fut = new DiscoTopologyFuture(ctx, awaitVer);

        fut.init();

        return fut;
    }

    /**
     * Gets discovery collection cache from SPI safely guarding against "floating" collections.
     *
     * @return Discovery collection cache.
     */
    public DiscoCache discoCache() {
        DiscoCache cur;

        while ((cur = discoCache.get()) == null)
            // Wrap the SPI collection to avoid possible floating collection.
            if (discoCache.compareAndSet(null, cur = new DiscoCache(localNode(), getSpi().getRemoteNodes())))
                return cur;

        return cur;
    }

    /** @return All non-daemon remote nodes in topology. */
    public Collection<GridNode> remoteNodes() {
        return discoCache().remoteNodes();
    }

    /** @return All non-daemon nodes in topology. */
    public Collection<GridNode> allNodes() {
        return discoCache().allNodes();
    }

    /**
     * Gets collection of nodes with version equal or greater than {@code ver}.
     *
     * @param ver Version to check.
     * @return Collection of nodes with version equal or greater than {@code ver}.
     */
    public Collection<GridNode> elderNodes(GridProductVersion ver) {
        return discoCache().elderNodes(ver);
    }

    /**
     * Gets topology grouped by node versions.
     *
     * @return Version to collection of nodes map.
     */
    public NavigableMap<GridProductVersion, Collection<GridNode>> topologyVersionMap() {
        return discoCache().versionsMap();
    }

    /** @return Full topology size. */
    public int size() {
        return discoCache().allNodes().size();
    }

    /**
     * Gets all nodes for given topology version.
     *
     * @param topVer Topology version.
     * @return Collection of cache nodes.
     */
    public Collection<GridNode> nodes(long topVer) {
        return resolveDiscoCache(null, topVer).allNodes();
    }

    /**
     * Gets cache nodes for cache with given name.
     *
     * @param cacheName Cache name.
     * @param topVer Topology version.
     * @return Collection of cache nodes.
     */
    public Collection<GridNode> cacheNodes(@Nullable String cacheName, long topVer) {
        return resolveDiscoCache(cacheName, topVer).cacheNodes(cacheName, topVer);
    }

    /**
     * Gets cache remote nodes for cache with given name.
     *
     * @param cacheName Cache name.
     * @param topVer Topology version.
     * @return Collection of cache nodes.
     */
    public Collection<GridNode> remoteCacheNodes(@Nullable String cacheName, long topVer) {
        return resolveDiscoCache(cacheName, topVer).remoteCacheNodes(cacheName, topVer);
    }

    /**
     * Gets cache nodes for cache with given name.
     *
     * @param cacheName Cache name.
     * @param topVer Topology version.
     * @return Collection of cache nodes.
     */
    public Collection<GridNode> aliveCacheNodes(@Nullable String cacheName, long topVer) {
        return resolveDiscoCache(cacheName, topVer).aliveCacheNodes(cacheName, topVer);
    }

    /**
     * Gets cache remote nodes for cache with given name.
     *
     * @param cacheName Cache name.
     * @param topVer Topology version.
     * @return Collection of cache nodes.
     */
    public Collection<GridNode> aliveRemoteCacheNodes(@Nullable String cacheName, long topVer) {
        return resolveDiscoCache(cacheName, topVer).aliveRemoteCacheNodes(cacheName, topVer);
    }

    /**
     * Gets cache nodes for cache with given name that participate in affinity calculation.
     *
     * @param cacheName Cache name.
     * @param topVer Topology version.
     * @return Collection of cache affinity nodes.
     */
    public Collection<GridNode> cacheAffinityNodes(@Nullable String cacheName, long topVer) {
        return resolveDiscoCache(cacheName, topVer).cacheAffinityNodes(cacheName, topVer);
    }

    /**
     * Checks if cache with given name has at least one node with near cache enabled.
     *
     * @param cacheName Cache name.
     * @param topVer Topology version.
     * @return {@code True} if cache with given name has at least one node with near cache enabled.
     */
    public boolean hasNearCache(@Nullable String cacheName, long topVer) {
        return resolveDiscoCache(cacheName, topVer).hasNearCache(cacheName);
    }

    /**
     * Gets discovery cache for given topology version.
     *
     * @param cacheName Cache name (participates in exception message).
     * @param topVer Topology version.
     * @return Discovery cache.
     */
    private DiscoCache resolveDiscoCache(@Nullable String cacheName, long topVer) {
        DiscoCache cache = topVer == -1 || topVer == topologyVersion() ? discoCache() : discoCacheHist.get(topVer);

        if (cache == null) {
            // Find the eldest acceptable discovery cache.
            Map.Entry<Long, DiscoCache> eldest = Collections.min(discoCacheHist.entrySet(), histCmp);

            if (topVer < eldest.getKey())
                cache = eldest.getValue();
        }

        if (cache == null) {
            throw new GridRuntimeException("Failed to resolve nodes topology [cacheName=" + cacheName +
                ", topVer=" + topVer + ", history=" + discoCacheHist.keySet() +
                ", locNode=" + ctx.discovery().localNode() + ']');
        }

        return cache;
    }

    /**
     * Gets topology by specified version from history storage.
     *
     * @param topVer Topology version.
     * @return Topology nodes.
     */
    public Collection<GridNode> topology(long topVer) {
        if (!histSupported)
            throw new UnsupportedOperationException("Current discovery SPI does not support " +
                "topology snapshots history (consider using TCP discovery SPI).");

        Map<Long, Collection<GridNode>> snapshots = topHist;

        return snapshots.get(topVer);
    }

    /** @return All daemon nodes in topology. */
    public Collection<GridNode> daemonNodes() {
        return discoCache().daemonNodes();
    }

    /** @return Local node. */
    public GridNode localNode() {
        return locNode == null ? getSpi().getLocalNode() : locNode;
    }

    /** @return Topology version. */
    public long topologyVersion() {
        return topVer.get();
    }

    /** @return Event that represents a local node joined to topology. */
    public GridDiscoveryEvent localJoinEvent() {
        return locJoinEvt;
    }

    /**
     * Gets first grid node start time, see {@link GridDiscoverySpi#getGridStartTime()}.
     *
     * @return Start time of the first grid node.
     */
    public long gridStartTime() {
        return getSpi().getGridStartTime();
    }

    /** Stops local node. */
    private void stopNode() {
        new Thread(
            new Runnable() {
                @Override public void run() {
                    ctx.markSegmented();

                    G.stop(ctx.gridName(), true);
                }
            }
        ).start();
    }

    /** Restarts JVM. */
    private void restartJvm() {
        new Thread(
            new Runnable() {
                @Override public void run() {
                    ctx.markSegmented();

                    G.restart(true);
                }
            }
        ).start();
    }

    /** Worker for network segment checks. */
    private class SegmentCheckWorker extends GridWorker {
        /** */
        private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();

        /**
         *
         */
        private SegmentCheckWorker() {
            super(ctx.gridName(), "disco-net-seg-chk-worker", log);

            assert hasRslvrs;
            assert segChkFreq > 0;
        }

        /**
         *
         */
        public void scheduleSegmentCheck() {
            queue.add(new Object());
        }

        /** {@inheritDoc} */
        @SuppressWarnings("StatementWithEmptyBody")
        @Override protected void body() throws InterruptedException {
            long lastChk = 0;

            while (!isCancelled()) {
                Object req = queue.poll(2000, MILLISECONDS);

                long now = U.currentTimeMillis();

                // Check frequency if segment check has not been requested.
                if (req == null && (segChkFreq == 0 || lastChk + segChkFreq >= now)) {
                    if (log.isDebugEnabled())
                        log.debug("Skipping segment check as it has not been requested and it is not time to check.");

                    continue;
                }

                // We should always check segment if it has been explicitly
                // requested (on any node failure or leave).
                assert req != null || lastChk + segChkFreq < now;

                // Drain queue.
                while (queue.poll() != null) {
                    // No-op.
                }

                if (lastSegChkRes.get()) {
                    boolean segValid = ctx.segmentation().isValidSegment();

                    lastChk = now;

                    if (!segValid) {
                        discoWrk.addEvent(EVT_NODE_SEGMENTED, 0, getSpi().getLocalNode(),
                            Collections.<GridNode>emptyList());

                        lastSegChkRes.set(false);
                    }

                    if (log.isDebugEnabled())
                        log.debug("Segment has been checked [requested=" + (req != null) + ", valid=" + segValid + ']');
                }
            }
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(SegmentCheckWorker.class, this);
        }
    }

    /** Worker for network segment checks. */
    private class ReconnectWorker extends GridWorker {
        /** */
        private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();

        /**
         *
         */
        private ReconnectWorker() {
            super(ctx.gridName(), "disco-recon-worker", log);

            assert ctx.config().getSegmentationPolicy() == RECONNECT;
        }

        /**
         *
         */
        public void scheduleReconnect() {
            queue.add(new Object());
        }

        /** {@inheritDoc} */
        @Override protected void body() throws InterruptedException {
            while (!isCancelled()) {
                queue.take();

                try {
                    U.warn(log, "Will try to reconnect discovery SPI to topology " +
                        "(according to configured segmentation policy).");

                    // Check only if resolvers were configured.
                    if (hasRslvrs)
                        checkSegmentOnStart();

                    topVer.set(0);

                    getSpi().reconnect();

                    // Refresh local node.
                    locNode = getSpi().getLocalNode();
                }
                catch (GridException e) {
                    U.error(log, "Failed to reconnect discovery SPI to topology (will stop node).", e);

                    stopNode();
                }
            }
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(ReconnectWorker.class, this);
        }
    }

    /** Worker for discovery events. */
    private class DiscoveryWorker extends GridWorker {
        /** Event queue. */
        private final BlockingQueue<GridTuple4<Integer, Long, GridNode, Collection<GridNode>>> evts =
            new LinkedBlockingQueue<>();

        /** Node segmented event fired flag. */
        private boolean nodeSegFired;

        /**
         *
         */
        private DiscoveryWorker() {
            super(ctx.gridName(), "discovery-worker", log);
        }

        /**
         * Method is called when any discovery event occurs.
         *
         * @param type Discovery event type. See {@link GridDiscoveryEvent} for more details.
         * @param topVer Topology version.
         * @param node Remote node this event is connected with.
         * @param topSnapshot Topology snapshot.
         */
        private void recordEvent(int type, long topVer, GridNode node, Collection<GridNode> topSnapshot) {
            assert node != null;

            if (ctx.event().isRecordable(type)) {
                GridDiscoveryEvent evt = new GridDiscoveryEvent();

                evt.nodeId(ctx.localNodeId());
                evt.eventNodeId(node.id());
                evt.type(type);
                evt.shadow(new GridDiscoveryNodeShadowAdapter(node));

                evt.topologySnapshot(topVer, new ArrayList<>(
                    F.viewReadOnly(topSnapshot, new C1<GridNode, GridNodeShadow>() {
                        @Override public GridNodeShadow apply(GridNode e) {
                            return new GridDiscoveryNodeShadowAdapter(e);
                        }
                    }, daemonFilter)));

                if (type == EVT_NODE_METRICS_UPDATED)
                    evt.message("Metrics were updated: " + node);

                else if (type == EVT_NODE_JOINED)
                    evt.message("Node joined: " + node);

                else if (type == EVT_NODE_LEFT)
                    evt.message("Node left: " + node);

                else if (type == EVT_NODE_FAILED)
                    evt.message("Node failed: " + node);

                else if (type == EVT_NODE_SEGMENTED)
                    evt.message("Node segmented: " + node);

                else if (type == EVT_NODE_RECONNECTED)
                    evt.message("Node reconnected: " + node);

                else
                    assert false;

                ctx.event().record(evt);
            }
        }

        /**
         * @param type Event type.
         * @param topVer Topology version.
         * @param node Node.
         * @param topSnapshot Topology snapshot.
         */
        void addEvent(int type, long topVer, GridNode node, Collection<GridNode> topSnapshot) {
            assert node != null;

            evts.add(F.t(type, topVer, node, topSnapshot));
        }

        /**
         * @param node Node to get a short description for.
         * @return Short description for the node to be used in 'quiet' mode.
         */
        private String quietNode(GridNode node) {
            assert node != null;

            return "nodeId8=" + node.id().toString().substring(0, 8) + ", " +
                "addrs=" + U.addressesAsString(node) + ", " +
                "order=" + node.order() + ", " +
                "CPUs=" + node.metrics().getTotalCpus();
        }

        /** {@inheritDoc} */
        @Override protected void body() throws InterruptedException {
            while (!isCancelled()) {
                try {
                    body0();
                }
                catch (InterruptedException e) {
                    throw e;
                }
                catch (Throwable t) {
                    U.error(log, "Unexpected exception in discovery worker thread (ignored).", t);
                }
            }
        }

        /** @throws InterruptedException If interrupted. */
        @SuppressWarnings("DuplicateCondition")
        private void body0() throws InterruptedException {
            GridTuple4<Integer, Long, GridNode, Collection<GridNode>> evt = evts.take();

            int type = evt.get1();

            long topVer = evt.get2();

            GridNode node = evt.get3();

            boolean isDaemon = node.isDaemon();

            boolean segmented = false;

            switch (type) {
                case EVT_NODE_JOINED: {
                    assert !discoOrdered || topVer == node.order() : "Invalid topology version [topVer=" + topVer +
                        ", node=" + node + ']';

                    try {
                        checkAttributes(F.asList(node));
                    }
                    catch (GridException e) {
                        U.warn(log, e.getMessage()); // We a have well-formed attribute warning here.
                    }

                    ctx.versionConverter().onNodeJoined(node);

                    if (!isDaemon) {
                        if (!isLocDaemon) {
                            if (log.isInfoEnabled())
                                log.info("Added new node to topology: " + node);

                            ackTopology(topVer, true);
                        }
                        else if (log.isDebugEnabled())
                            log.debug("Added new node to topology: " + node);
                    }
                    else if (log.isDebugEnabled())
                        log.debug("Added new daemon node to topology: " + node);

                    break;
                }

                case EVT_NODE_LEFT: {
                    // Check only if resolvers were configured.
                    if (hasRslvrs)
                        segChkWrk.scheduleSegmentCheck();

                    ctx.versionConverter().onNodeLeft(node);

                    if (!isDaemon) {
                        if (!isLocDaemon) {
                            if (log.isInfoEnabled())
                                log.info("Node left topology: " + node);

                            ackTopology(topVer, true);
                        }
                        else if (log.isDebugEnabled())
                            log.debug("Node left topology: " + node);
                    }
                    else if (log.isDebugEnabled())
                        log.debug("Daemon node left topology: " + node);

                    break;
                }

                case EVT_NODE_FAILED: {
                    // Check only if resolvers were configured.
                    if (hasRslvrs)
                        segChkWrk.scheduleSegmentCheck();

                    ctx.versionConverter().onNodeLeft(node);

                    if (!isDaemon) {
                        if (!isLocDaemon) {
                            U.warn(log, "Node FAILED: " + node);

                            ackTopology(topVer, true);
                        }
                        else if (log.isDebugEnabled())
                            log.debug("Node FAILED: " + node);
                    }
                    else if (log.isDebugEnabled())
                        log.debug("Daemon node FAILED: " + node);

                    break;
                }

                case EVT_NODE_SEGMENTED: {
                    assert F.eqNodes(localNode(), node);

                    if (nodeSegFired) {
                        if (log.isDebugEnabled()) {
                            log.debug("Ignored node segmented event [type=EVT_NODE_SEGMENTED, " +
                                "node=" + node + ']');
                        }

                        return;
                    }

                    // Ignore all further EVT_NODE_SEGMENTED events
                    // until EVT_NODE_RECONNECTED is fired.
                    nodeSegFired = true;

                    lastLoggedTop.set(0);

                    segmented = true;

                    if (!isLocDaemon)
                        U.warn(log, "Local node SEGMENTED: " + node);
                    else if (log.isDebugEnabled())
                        log.debug("Local node SEGMENTED: " + node);

                    break;
                }

                case EVT_NODE_RECONNECTED: {
                    assert !discoOrdered || topVer == node.order() : "Invalid topology version [topVer=" + topVer +
                        ", node=" + node + ']';

                    assert F.eqNodes(locNode, node);

                    // Do not ignore EVT_NODE_SEGMENTED events any more.
                    nodeSegFired = false;

                    // Allow background segment check.
                    lastSegChkRes.set(true);

                    if (!isLocDaemon) {
                        if (log.isInfoEnabled())
                            log.info("Local node RECONNECTED: " + node);

                        if (log.isQuiet())
                            U.quiet(false, "Local node RECONNECTED [" + quietNode(node) + ']');

                        ackTopology(topVer, true);
                    }
                    else if (log.isDebugEnabled())
                        log.debug("Local node RECONNECTED: " + node);

                    break;
                }

                // Don't log metric update to avoid flooding the log.
                case EVT_NODE_METRICS_UPDATED:
                    break;

                default:
                    assert false : "Invalid discovery event: " + type;
            }

            recordEvent(type, topVer, node, evt.get4());

            if (segmented)
                onSegmentation();
        }

        /**
         *
         */
        private void onSegmentation() {
            GridSegmentationPolicy segPlc = ctx.config().getSegmentationPolicy();

            switch (segPlc) {
                case RECONNECT:
                    // Disconnect SPI synchronously to maintain consistent
                    // local topology.
                    try {
                        getSpi().disconnect();

                        discoCacheHist.clear();

                        reconWrk.scheduleReconnect();
                    }
                    catch (GridSpiException e) {
                        U.error(log, "Failed to disconnect discovery SPI (will stop node).", e);

                        // Stop from separate thread only.
                        stopNode();
                    }

                    break;

                case RESTART_JVM:
                    try {
                        getSpi().disconnect();
                    }
                    catch (GridSpiException e) {
                        U.error(log, "Failed to disconnect discovery SPI.", e);
                    }

                    U.warn(log, "Restarting JVM according to configured segmentation policy.");

                    restartJvm();

                    break;

                case STOP:
                    try {
                        getSpi().disconnect();
                    }
                    catch (GridSpiException e) {
                        U.error(log, "Failed to disconnect discovery SPI.", e);
                    }

                    U.warn(log, "Stopping local node according to configured segmentation policy.");

                    stopNode();

                    break;

                default:
                    assert segPlc == NOOP : "Unsupported segmentation policy value: " + segPlc;
            }
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(DiscoveryWorker.class, this);
        }
    }

    /** Discovery topology future. */
    private static class DiscoTopologyFuture extends GridFutureAdapter<Long> implements GridLocalEventListener {
        /** Topology await version. */
        private long awaitVer;

        /** Empty constructor required by {@link Externalizable}. */
        public DiscoTopologyFuture() {
            // No-op.
        }

        /**
         * @param ctx Context.
         * @param awaitVer Await version.
         */
        private DiscoTopologyFuture(GridKernalContext ctx, long awaitVer) {
            super(ctx);

            this.awaitVer = awaitVer;
        }

        /** Initializes future. */
        private void init() {
            ctx.event().addLocalEventListener(this, EVT_NODE_JOINED, EVT_NODE_LEFT, EVT_NODE_FAILED);

            // Close potential window.
            long topVer = ctx.discovery().topologyVersion();

            if (topVer >= awaitVer)
                onDone(topVer);
        }

        /** {@inheritDoc} */
        @Override public boolean onDone(@Nullable Long res, @Nullable Throwable err) {
            if (super.onDone(res, err)) {
                ctx.event().removeLocalEventListener(this, EVT_NODE_JOINED, EVT_NODE_LEFT, EVT_NODE_FAILED);

                return true;
            }

            return false;
        }

        /** {@inheritDoc} */
        @Override public void onEvent(GridEvent evt) {
            assert evt.type() == EVT_NODE_JOINED || evt.type() == EVT_NODE_LEFT || evt.type() == EVT_NODE_FAILED;

            GridDiscoveryEvent discoEvt = (GridDiscoveryEvent)evt;

            if (discoEvt.topologyVersion() >= awaitVer)
                onDone(discoEvt.topologyVersion());
        }
    }

    /** Cache for discovery collections. */
    private class DiscoCache {
        /** Remote nodes. */
        private final List<GridNode> rmtNodes;

        /** All nodes. */
        private final List<GridNode> allNodes;

        /** Cache nodes by cache name. */
        private final Map<String, Collection<GridNode>> allCacheNodes;

        /** Remote cache nodes by cache name. */
        private final Map<String, Collection<GridNode>> rmtCacheNodes;

        /** Cache nodes by cache name. */
        private final Map<String, Collection<GridNode>> affCacheNodes;

        /** Caches where at least one node has near cache enabled. */
        private final Set<String> nearEnabledCaches;

        /** Nodes grouped by version. */
        private final NavigableMap<GridProductVersion, Collection<GridNode>> nodesByVer;

        /** Daemon nodes. */
        private final List<GridNode> daemonNodes;

        /** Node map. */
        private final Map<UUID, GridNode> nodeMap;

        /** Local node. */
        private final GridNode loc;

        /** Highest node order. */
        private final long maxOrder;

        /**
         * Cached alive nodes list. As long as this collection doesn't accept {@code null}s use {@link
         * #maskNull(String)} before passing raw cache names to it.
         */
        private final ConcurrentMap<String, Collection<GridNode>> aliveCacheNodes;

        /**
         * Cached alive remote nodes list. As long as this collection doesn't accept {@code null}s use {@link
         * #maskNull(String)} before passing raw cache names to it.
         */
        private final ConcurrentMap<String, Collection<GridNode>> aliveRmtCacheNodes;

        /**
         * @param loc Local node.
         * @param rmts Remote nodes.
         */
        private DiscoCache(GridNode loc, Collection<GridNode> rmts) {
            this.loc = loc;

            rmtNodes = Collections.unmodifiableList(new ArrayList<>(F.view(rmts, daemonFilter)));

            assert !rmtNodes.contains(loc) : "Remote nodes collection shouldn't contain local node" +
                " [rmtNodes=" + rmtNodes + ", loc=" + loc + ']';

            List<GridNode> all = new ArrayList<>(rmtNodes.size() + 1);

            if (!loc.isDaemon())
                all.add(loc);

            all.addAll(rmtNodes);

            allNodes = Collections.unmodifiableList(all);

            Map<String, Collection<GridNode>> cacheMap =
                new HashMap<>(allNodes.size(), 1.0f);
            Map<String, Collection<GridNode>> rmtCacheMap =
                new HashMap<>(allNodes.size(), 1.0f);
            Map<String, Collection<GridNode>> dhtNodesMap =
                new HashMap<>(allNodes.size(), 1.0f);

            aliveCacheNodes = new ConcurrentHashMap8<>(allNodes.size(), 1.0f);
            aliveRmtCacheNodes = new ConcurrentHashMap8<>(allNodes.size(), 1.0f);
            nodesByVer = new TreeMap<>();

            long maxOrder0 = 0;

            Set<String> nearEnabledSet = new HashSet<>();

            for (GridNode node : allNodes) {
                if (node.order() > maxOrder0)
                    maxOrder0 = node.order();

                GridCacheAttributes[] caches = node.attribute(ATTR_CACHE);

                if (caches != null) {
                    for (GridCacheAttributes attrs : caches) {
                        addToMap(cacheMap, attrs.cacheName(), node);

                        if (alive(node.id()))
                            addToMap(aliveCacheNodes, maskNull(attrs.cacheName()), node);

                        if (attrs.isAffinityNode())
                            addToMap(dhtNodesMap, attrs.cacheName(), node);

                        if (attrs.nearCacheEnabled())
                            nearEnabledSet.add(attrs.cacheName());

                        if (!loc.id().equals(node.id())) {
                            addToMap(rmtCacheMap, attrs.cacheName(), node);

                            if (alive(node.id()))
                                addToMap(aliveRmtCacheNodes, maskNull(attrs.cacheName()), node);
                        }
                    }
                }

                GridProductVersion nodeVer = U.productVersion(node);

                // Create collection for this version if it does not exist.
                Collection<GridNode> nodes = nodesByVer.get(nodeVer);

                if (nodes == null) {
                    nodes = new ArrayList<>(allNodes.size());

                    nodesByVer.put(nodeVer, nodes);
                }

                nodes.add(node);
            }

            // Need second iteration to add this node to all previous node versions.
            for (GridNode node : allNodes) {
                GridProductVersion nodeVer = U.productVersion(node);

                // Get all versions lower or equal node's version.
                NavigableMap<GridProductVersion, Collection<GridNode>> updateView =
                    nodesByVer.headMap(nodeVer, false);

                for (Collection<GridNode> prevVersions : updateView.values())
                    prevVersions.add(node);
            }

            maxOrder = maxOrder0;

            allCacheNodes = Collections.unmodifiableMap(cacheMap);
            rmtCacheNodes = Collections.unmodifiableMap(rmtCacheMap);
            affCacheNodes = Collections.unmodifiableMap(dhtNodesMap);
            nearEnabledCaches = Collections.unmodifiableSet(nearEnabledSet);

            daemonNodes = Collections.unmodifiableList(new ArrayList<>(
                F.view(F.concat(false, loc, rmts), F0.not(daemonFilter))));

            Map<UUID, GridNode> nodeMap = new HashMap<>(allNodes().size() + daemonNodes.size(), 1.0f);

            for (GridNode n : F.concat(false, allNodes(), daemonNodes()))
                nodeMap.put(n.id(), n);

            this.nodeMap = nodeMap;
        }

        /**
         * Adds node to map.
         *
         * @param cacheMap Map to add to.
         * @param cacheName Cache name.
         * @param rich Node to add
         */
        private void addToMap(Map<String, Collection<GridNode>> cacheMap, String cacheName, GridNode rich) {
            Collection<GridNode> cacheNodes = cacheMap.get(cacheName);

            if (cacheNodes == null) {
                cacheNodes = new ArrayList<>(allNodes.size());

                cacheMap.put(cacheName, cacheNodes);
            }

            cacheNodes.add(rich);
        }

        /** @return Local node. */
        GridNode localNode() {
            return loc;
        }

        /** @return Remote nodes. */
        Collection<GridNode> remoteNodes() {
            return rmtNodes;
        }

        /** @return All nodes. */
        Collection<GridNode> allNodes() {
            return allNodes;
        }

        /**
         * Gets collection of nodes which have version equal or greater than {@code ver}.
         *
         * @param ver Version to check.
         * @return Collection of nodes with version equal or greater than {@code ver}.
         */
        Collection<GridNode> elderNodes(GridProductVersion ver) {
            Map.Entry<GridProductVersion, Collection<GridNode>> entry = nodesByVer.ceilingEntry(ver);

            if (entry == null)
                return Collections.emptyList();

            return entry.getValue();
        }

        /**
         * @return Versions map.
         */
        NavigableMap<GridProductVersion, Collection<GridNode>> versionsMap() {
            return nodesByVer;
        }

        /**
         * Gets all nodes that have cache with given name.
         *
         * @param cacheName Cache name.
         * @param topVer Topology version.
         * @return Collection of nodes.
         */
        Collection<GridNode> cacheNodes(@Nullable String cacheName, final long topVer) {
            return filter(topVer, allCacheNodes.get(cacheName));
        }

        /**
         * Gets all remote nodes that have cache with given name.
         *
         * @param cacheName Cache name.
         * @param topVer Topology version.
         * @return Collection of nodes.
         */
        Collection<GridNode> remoteCacheNodes(@Nullable String cacheName, final long topVer) {
            return filter(topVer, rmtCacheNodes.get(cacheName));
        }

        /**
         * Gets all nodes that have cache with given name and should participate in affinity calculation. With
         * partitioned cache nodes with near-only cache no not participate in affinity node calculation.
         *
         * @param cacheName Cache name.
         * @param topVer Topology version.
         * @return Collection of nodes.
         */
        Collection<GridNode> cacheAffinityNodes(@Nullable String cacheName, final long topVer) {
            return filter(topVer, affCacheNodes.get(cacheName));
        }

        /**
         * Gets all alive nodes that have cache with given name.
         *
         * @param cacheName Cache name.
         * @param topVer Topology version.
         * @return Collection of nodes.
         */
        Collection<GridNode> aliveCacheNodes(@Nullable String cacheName, final long topVer) {
            return filter(topVer, aliveCacheNodes.get(maskNull(cacheName)));
        }

        /**
         * Gets all alive remote nodes that have cache with given name.
         *
         * @param cacheName Cache name.
         * @param topVer Topology version.
         * @return Collection of nodes.
         */
        Collection<GridNode> aliveRemoteCacheNodes(@Nullable String cacheName, final long topVer) {
            return filter(topVer, aliveRmtCacheNodes.get(maskNull(cacheName)));
        }

        /**
         * Checks if cache with given name has at least one node with near cache enabled.
         *
         * @param cacheName Cache name.
         * @return {@code True} if cache with given name has at least one node with near cache enabled.
         */
        boolean hasNearCache(@Nullable String cacheName) {
            return nearEnabledCaches.contains(cacheName);
        }

        /**
         * Removes left node from cached alives lists.
         *
         * @param leftNode Left node.
         */
        void updateAlives(GridNode leftNode) {
            if (leftNode.order() > maxOrder)
                return;

            filterNodeMap(aliveCacheNodes, leftNode);

            filterNodeMap(aliveRmtCacheNodes, leftNode);
        }

        /**
         * Creates a copy of nodes map without the given node.
         *
         * @param map Map to copy.
         * @param exclNode Node to exclude.
         */
        private void filterNodeMap(ConcurrentMap<String, Collection<GridNode>> map, final GridNode exclNode) {
            GridPredicate<GridNode> p = new P1<GridNode>() {
                @Override public boolean apply(GridNode e) {
                    return exclNode.equals(e);
                }
            };

            for (String cacheName : U.cacheNames(exclNode)) {
                String maskedName = maskNull(cacheName);

                while (true) {
                    Collection<GridNode> oldNodes = map.get(maskedName);

                    if (oldNodes == null || oldNodes.isEmpty())
                        break;

                    Collection<GridNode> newNodes = F.lose(oldNodes, true, p);

                    if (map.replace(maskedName, oldNodes, newNodes))
                        break;
                }
            }
        }

        /**
         * Replaces {@code null} with {@code NULL_CACHE_NAME}.
         *
         * @param cacheName Cache name.
         * @return Masked name.
         */
        private String maskNull(@Nullable String cacheName) {
            return cacheName == null ? NULL_CACHE_NAME : cacheName;
        }

        /**
         * @param topVer Topology version.
         * @param nodes Nodes.
         * @return Filtered collection (potentially empty, but never {@code null}).
         */
        private Collection<GridNode> filter(final long topVer, @Nullable Collection<GridNode> nodes) {
            if (nodes == null)
                return Collections.emptyList();

            // If no filtering needed, return original collection.
            return nodes.isEmpty() || topVer < 0 || topVer >= maxOrder ?
                nodes :
                F.view(nodes, new P1<GridNode>() {
                    @Override public boolean apply(GridNode node) {
                        return node.order() <= topVer;
                    }
                });
        }

        /** @return Daemon nodes. */
        Collection<GridNode> daemonNodes() {
            return daemonNodes;
        }

        /**
         * @param id Node ID.
         * @return Node.
         */
        @Nullable GridNode node(UUID id) {
            return nodeMap.get(id);
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return S.toString(DiscoCache.class, this, "allNodesWithDaemons", U.toShortString(allNodes));
        }
    }
}