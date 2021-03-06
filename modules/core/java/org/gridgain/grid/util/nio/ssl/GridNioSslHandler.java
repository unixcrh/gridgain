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

package org.gridgain.grid.util.nio.ssl;

import org.gridgain.grid.*;
import org.gridgain.grid.logger.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.gridgain.grid.util.nio.*;

import javax.net.ssl.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.locks.*;

import static javax.net.ssl.SSLEngineResult.*;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.*;
import static javax.net.ssl.SSLEngineResult.Status.*;

/**
 * Class that encapsulate the per-session SSL state, encoding and decoding logic.
 *
 * @author @java.author
 * @version @java.version
 */
class GridNioSslHandler extends ReentrantLock {
    /** Grid logger. */
    private GridLogger log;

    /** SSL engine. */
    private SSLEngine sslEngine;

    /** Session of this handler. */
    private GridNioSession ses;

    /** Handshake completion flag. */
    private boolean handshakeFinished;

    /** Flag to initiate session opened event on first handshake. */
    private boolean initHandshakeComplete;

    /** Engine handshake status. */
    private HandshakeStatus handshakeStatus;

    /** Output buffer into which encrypted data will be written. */
    private ByteBuffer outNetBuf;

    /** Input buffer from which SSL engine will decrypt data. */
    private ByteBuffer inNetBuf;

    /** Empty buffer used in handshake procedure.  */
    private ByteBuffer handshakeBuf = ByteBuffer.allocate(0);

    /** Application buffer. */
    private ByteBuffer appBuf;

    /** Parent filter. */
    private GridNioSslFilter parent;

    /** Pre-handshake write requests. */
    private Queue<WriteRequest> deferredWriteQueue = new LinkedList<>();

    /**
     * Creates handler.
     *
     * @param parent Parent SSL filter.
     * @param ses Session for which this handler was created.
     * @param engine SSL engine instance for this handler.
     * @param log Logger to use.
     * @throws SSLException If exception occurred when starting SSL handshake.
     */
    GridNioSslHandler(GridNioSslFilter parent, GridNioSession ses, SSLEngine engine, GridLogger log)
        throws SSLException {
        assert parent != null;
        assert ses != null;
        assert engine != null;
        assert log != null;

        this.parent = parent;
        this.ses = ses;
        this.log = log;

        sslEngine = engine;

        sslEngine.beginHandshake();

        handshakeStatus = sslEngine.getHandshakeStatus();

        // Allocate a little bit more so SSL engine would not return buffer overflow status.
        int netBufSize = sslEngine.getSession().getPacketBufferSize() + 50;

        outNetBuf = ByteBuffer.allocate(netBufSize);
        inNetBuf = ByteBuffer.allocate(netBufSize);

        // Initially buffer is empty.
        outNetBuf.position(0);
        outNetBuf.limit(0);

        int appBufSize = Math.max(sslEngine.getSession().getApplicationBufferSize() + 50, netBufSize * 2);

        appBuf = ByteBuffer.allocate(appBufSize);

        if (log.isDebugEnabled())
            log.debug("Started SSL session [netBufSize=" + netBufSize + ", appBufSize=" + appBufSize + ']');
    }

    /**
     * @return Application buffer with decoded data.
     */
    ByteBuffer getApplicationBuffer() {
        return appBuf;
    }

    /**
     * Shuts down the handler.
     */
    void shutdown() {
        try {
            sslEngine.closeInbound();
        }
        catch (SSLException e) {
            // According to javadoc, the only case when exception is thrown is when no close_notify
            // message was received before TCP connection get closed.
            if (log.isDebugEnabled())
                log.debug("Unable to correctly close inbound data stream (will ignore) [msg=" + e.getMessage() +
                    ", ses=" + ses + ']');
        }
    }

    /**
     * Performs handshake procedure with remote peer.
     *
     * @throws GridNioException If filter processing has thrown an exception.
     * @throws SSLException If failed to process SSL data.
     */
    void handshake() throws GridException, SSLException {
        if (log.isDebugEnabled())
            log.debug("Entered handshake(): [handshakeStatus=" + handshakeStatus + ", ses=" + ses + ']');

        boolean loop = true;

        while (loop) {
            switch (handshakeStatus) {
                case NOT_HANDSHAKING:
                case FINISHED: {
                    SSLSession sslSes = sslEngine.getSession();

                    if (log.isDebugEnabled())
                        log.debug("Finished ssl handshake [protocol=" + sslSes.getProtocol() + ", cipherSuite=" +
                            sslSes.getCipherSuite() + ", ses=" + ses + ']');

                    handshakeFinished = true;

                    if (!initHandshakeComplete) {
                        initHandshakeComplete = true;

                        parent.proceedSessionOpened(ses);
                    }

                    loop = false;

                    break;
                }

                case NEED_TASK: {
                    if (log.isDebugEnabled())
                        log.debug("Need to run ssl tasks: " + ses);

                    handshakeStatus = runTasks();

                    break;
                }

                case NEED_UNWRAP: {
                    if (log.isDebugEnabled())
                        log.debug("Need to unwrap incoming data: " + ses);

                    Status status = unwrapHandshake();

                    if (status == BUFFER_UNDERFLOW && handshakeStatus != FINISHED ||
                        sslEngine.isInboundDone())
                        // Either there is no enough data in buffer or session was closed.
                        loop = false;

                    break;
                }

                case NEED_WRAP: {
                    // If the output buffer has remaining data, clear it.
                    if (outNetBuf.hasRemaining())
                        U.warn(log, "Output net buffer has unsent bytes during handshake (will clear): " + ses);

                    outNetBuf.clear();

                    SSLEngineResult res = sslEngine.wrap(handshakeBuf, outNetBuf);

                    outNetBuf.flip();

                    handshakeStatus = res.getHandshakeStatus();

                    if (log.isDebugEnabled())
                        log.debug("Wrapped handshake data [status=" + res.getStatus() + ", handshakeStatus=" +
                            handshakeStatus + ", ses=" + ses + ']');

                    writeNetBuffer();

                    break;
                }

                default: {
                    throw new IllegalStateException("Invalid handshake status in handshake method [handshakeStatus=" +
                        handshakeStatus + ", ses=" + ses + ']');
                }
            }
        }

        if (log.isDebugEnabled())
            log.debug("Leaved handshake(): [handshakeStatus=" + handshakeStatus + ", ses=" + ses + ']');
    }

    /**
     * Called by SSL filter when new message was received.
     *
     * @param buf Received message.
     * @throws GridNioException If exception occurred while forwarding events to underlying filter.
     * @throws SSLException If failed to process SSL data.
     */
    void messageReceived(ByteBuffer buf) throws GridException, SSLException {
        if (buf.limit() > inNetBuf.remaining()) {
            inNetBuf = GridNioSslFilter.expandBuffer(inNetBuf, inNetBuf.capacity() + buf.limit() * 2);

            appBuf = GridNioSslFilter.expandBuffer(appBuf, inNetBuf.capacity() * 2);

            if (log.isDebugEnabled())
                log.debug("Expanded buffers [inNetBufCapacity=" + inNetBuf.capacity() + ", appBufCapacity=" +
                    appBuf.capacity() + ", ses=" + ses + ", ");
        }

        // append buf to inNetBuffer
        inNetBuf.put(buf);

        if (!handshakeFinished)
            handshake();
        else
            unwrapData();

        if (isInboundDone()) {
            int newPosition = buf.position() - inNetBuf.position();

            if (newPosition >= 0) {
                buf.position(newPosition);

                // If we received close_notify but not all bytes has been read by SSL engine, print a warning.
                if (buf.hasRemaining())
                    U.warn(log, "Got unread bytes after receiving close_notify message (will ignore): " + ses);
            }

            inNetBuf.clear();
        }
    }

    /**
     * Encrypts data to be written to the network.
     *
     * @param src data to encrypt
     * @throws SSLException on errors
     */
    void encrypt(ByteBuffer src) throws SSLException {
        assert handshakeFinished;
        assert isHeldByCurrentThread();

        // The data buffer is (must be) empty, we can reuse the entire
        // buffer.
        outNetBuf.clear();

        // Loop until there is no more data in src
        while (src.hasRemaining()) {
            int outNetRemaining = outNetBuf.capacity() - outNetBuf.position();

            if (outNetRemaining < src.remaining() * 2) {
                outNetBuf = GridNioSslFilter.expandBuffer(outNetBuf, Math.max(
                    outNetBuf.position() + src.remaining() * 2, outNetBuf.capacity() * 2));

                if (log.isDebugEnabled())
                    log.debug("Expanded output net buffer [outNetBufCapacity=" + outNetBuf.capacity() + ", ses=" +
                        ses + ']');
            }

            SSLEngineResult res = sslEngine.wrap(src, outNetBuf);

            if (log.isDebugEnabled())
                log.debug("Encrypted data [status=" + res.getStatus() + ", handshakeStaus=" +
                    res.getHandshakeStatus() + ", ses=" + ses + ']');

            if (res.getStatus() == SSLEngineResult.Status.OK) {
                if (res.getHandshakeStatus() == NEED_TASK)
                    runTasks();
            }
            else
                throw new SSLException("Failed to encrypt data (SSL engine error) [status=" + res.getStatus() +
                    ", handshakeStatus=" + res.getHandshakeStatus() + ", ses=" + ses + ']');
        }

        outNetBuf.flip();
    }

    /**
     * Checks if SSL handshake is finished.
     *
     * @return {@code True} if handshake is finished.
     */
    boolean isHandshakeFinished() {
        return handshakeFinished;
    }

    /**
     * @return {@code True} if inbound data stream has ended, i.e. SSL engine received
     * <tt>close_notify</tt> message.
     */
    boolean isInboundDone() {
        return sslEngine.isInboundDone();
    }

    /**
     * @return {@code True} if outbound data stream has closed, i.e. SSL engine encoded
     * <tt>close_notify</tt> message.
     */
    boolean isOutboundDone() {
        return sslEngine.isOutboundDone();
    }

    /**
     * Adds write request to the queue.
     *
     * @param buf Buffer to write.
     * @return Write future.
     */
    GridNioFuture<?> deferredWrite(ByteBuffer buf) {
        assert isHeldByCurrentThread();

        GridNioEmbeddedFuture<Object> fut = new GridNioEmbeddedFuture<>();

        ByteBuffer cp = GridNioSslFilter.copy(buf);

        deferredWriteQueue.offer(new WriteRequest(fut, cp));

        return fut;
    }

    /**
     * Flushes all deferred write events.
     * @throws GridNioException If failed to forward writes to the filter.
     */
    void flushDeferredWrites() throws GridException {
        assert isHeldByCurrentThread();
        assert handshakeFinished;

        while (!deferredWriteQueue.isEmpty()) {
            WriteRequest req = deferredWriteQueue.poll();

            req.future().onDone((GridNioFuture<Object>)parent.proceedSessionWrite(ses, req.buffer()));
        }
    }

    /**
     * Writes close_notify message to the network output buffer.
     *
     * @throws SSLException If wrap failed or SSL engine does not get closed
     * after wrap.
     * @return {@code True} if <tt>close_notify</tt> message was encoded, {@code false} if outbound
     *      stream was already closed.
     */
    boolean closeOutbound() throws SSLException {
        assert isHeldByCurrentThread();

        if (!sslEngine.isOutboundDone()) {
            sslEngine.closeOutbound();

            outNetBuf.clear();

            SSLEngineResult res = sslEngine.wrap(handshakeBuf, outNetBuf);

            if (res.getStatus() != CLOSED)
                throw new SSLException("Incorrect SSL engine status after closeOutbound call [status=" +
                    res.getStatus() + ", handshakeStatus=" + res.getHandshakeStatus() + ", ses=" + ses + ']');

            outNetBuf.flip();

            return true;
        }

        return false;
    }

    /**
     * Copies data from out net buffer and passes it to the underlying chain.
     *
     * @return Write future.
     * @throws GridNioException If send failed.
     */
    GridNioFuture<?> writeNetBuffer() throws GridException {
        assert isHeldByCurrentThread();

        ByteBuffer cp = GridNioSslFilter.copy(outNetBuf);

        return parent.proceedSessionWrite(ses, cp);
    }

    /**
     * Unwraps user data to the application buffer.
     *
     * @throws SSLException If failed to process SSL data.
     * @throws GridNioException If failed to pass events to the next filter.
     */
    private void unwrapData() throws GridException, SSLException {
        if (log.isDebugEnabled())
            log.debug("Unwrapping received data: " + ses);

        // Flip buffer so we can read it.
        inNetBuf.flip();

        SSLEngineResult res = unwrap0();

        // prepare to be written again
        inNetBuf.compact();

        checkStatus(res);

        renegotiateIfNeeded(res);
    }

    /**
     * Unwraps handshake data and processes it.
     *
     * @return Status.
     * @throws SSLException If SSL exception occurred while unwrapping.
     * @throws GridNioException If failed to pass event to the next filter.
     */
    private Status unwrapHandshake() throws SSLException, GridException {
        // Flip input buffer so we can read the collected data.
        inNetBuf.flip();

        SSLEngineResult res = unwrap0();
        handshakeStatus = res.getHandshakeStatus();

        checkStatus(res);

        // If handshake finished, no data was produced, and the status is still ok,
        // try to unwrap more
        if (handshakeStatus == FINISHED && res.getStatus() == Status.OK && inNetBuf.hasRemaining()) {
            res = unwrap0();

            handshakeStatus = res.getHandshakeStatus();

            // prepare to be written again
            inNetBuf.compact();

            renegotiateIfNeeded(res);
        }
        else
            // prepare to be written again
            inNetBuf.compact();

        return res.getStatus();
    }

    /**
     * Check status and retry the negotiation process if needed.
     *
     * @param res Result.
     * @throws GridNioException If exception occurred during handshake.
     * @throws SSLException If failed to process SSL data
     */
    private void renegotiateIfNeeded(SSLEngineResult res) throws GridException, SSLException {
        if (res.getStatus() != CLOSED && res.getStatus() != BUFFER_UNDERFLOW
            && res.getHandshakeStatus() != NOT_HANDSHAKING) {
            // Renegotiation required.
            handshakeStatus = res.getHandshakeStatus();

            if (log.isDebugEnabled())
                log.debug("Renegotiation requested [status=" + res.getStatus() + ", handshakeStatus = " +
                    handshakeStatus + "ses=" + ses + ']');

            handshakeFinished = false;

            handshake();
        }
    }

    /**
     * @param res SSL engine result.
     * @throws SSLException If status is not acceptable.
     */
    private void checkStatus(SSLEngineResult res)
        throws SSLException {

        SSLEngineResult.Status status = res.getStatus();

        if (status != Status.OK && status != CLOSED && status != BUFFER_UNDERFLOW)
            throw new SSLException("Failed to unwrap incoming data (SSL engine error) [ses" + ses + ", status=" +
                status + ']');
    }

    /**
     * Performs raw unwrap from network read buffer.
     *
     * @return Result.
     * @throws SSLException If SSL exception occurs.
     */
    private SSLEngineResult unwrap0() throws SSLException {
        SSLEngineResult res;

        do {
            res = sslEngine.unwrap(inNetBuf, appBuf);

            if (log.isDebugEnabled())
                log.debug("Unwrapped raw data [status=" + res.getStatus() + ", handshakeStatus=" +
                    res.getHandshakeStatus() + ", ses=" + ses + ']');
        }
        while (res.getStatus() == Status.OK && (handshakeFinished && res.getHandshakeStatus() == NOT_HANDSHAKING ||
            res.getHandshakeStatus() == NEED_UNWRAP));

        return res;
    }

    /**
     * Runs all tasks needed to continue SSL work.
     *
     * @return Handshake status after running all tasks.
     */
    private HandshakeStatus runTasks() {
        Runnable runnable;

        while ((runnable = sslEngine.getDelegatedTask()) != null) {
            if (log.isDebugEnabled())
                log.debug("Running SSL engine task [task=" + runnable + ", ses=" + ses + ']');

            runnable.run();
        }

        if (log.isDebugEnabled())
            log.debug("Finished running SSL engine tasks [handshakeStatus=" + sslEngine.getHandshakeStatus() +
                ", ses=" + ses + ']');

        return sslEngine.getHandshakeStatus();
    }

    /**
     * Write request for cases while handshake is not finished yet.
     */
    private static class WriteRequest {
        /** Future that should be completed. */
        private GridNioEmbeddedFuture<Object> fut;

        /** Buffer needed to be written. */
        private ByteBuffer buf;

        /**
         * Creates write request.
         *
         * @param fut Future.
         * @param buf Buffer to write.
         */
        private WriteRequest(GridNioEmbeddedFuture<Object> fut, ByteBuffer buf) {
            this.fut = fut;
            this.buf = buf;
        }

        /**
         * @return Future.
         */
        public GridNioEmbeddedFuture<Object> future() {
            return fut;
        }

        /**
         * @return Buffer.
         */
        public ByteBuffer buffer() {
            return buf;
        }
    }
}
