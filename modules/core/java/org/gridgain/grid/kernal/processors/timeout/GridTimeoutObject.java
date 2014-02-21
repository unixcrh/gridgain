// @java.file.header

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.timeout;

import org.gridgain.grid.util.*;

/**
 * All objects that can timeout should implement this interface.
 *
 * @author @java.author
 * @version @java.version
 */
public interface GridTimeoutObject {
    /**
     * @return ID of the object.
     */
    public GridUuid timeoutId();

    /**
     * @return End time.
     */
    public long endTime();

    /**
     * Timeout callback.
     */
    public void onTimeout();
}