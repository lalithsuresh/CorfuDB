package org.corfudb.runtime.view;

import lombok.extern.slf4j.Slf4j;
import org.corfudb.protocols.logprotocol.IDivisibleEntry;
import org.corfudb.protocols.logprotocol.StreamCOWEntry;
import org.corfudb.protocols.wireprotocol.TokenResponse;
import org.corfudb.protocols.wireprotocol.TxResolutionInfo;
import org.corfudb.runtime.CorfuRuntime;
import org.corfudb.runtime.exceptions.OverwriteException;
import org.corfudb.runtime.exceptions.ReplexOverwriteException;
import org.corfudb.runtime.view.stream.BackpointerStreamView;
import org.corfudb.runtime.view.stream.IStreamView;
import org.corfudb.runtime.view.stream.ReplexStreamView;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Created by mwei on 12/11/15.
 */
@Slf4j
public class StreamsView {

    /**
     * The org.corfudb.runtime which backs this view.
     */
    CorfuRuntime runtime;

    public StreamsView(CorfuRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Get a view on a stream. The view has its own pointer to the stream.
     *
     * @param stream The UUID of the stream to get a view on.
     * @return A view
     */
    public IStreamView get(UUID stream) {
        return getReplicationMode().getStreamView(runtime, stream);
    }

    private Layout.ReplicationMode getReplicationMode() {
        return runtime.getLayoutView().getLayout().getSegments().get(
                runtime.getLayoutView().getLayout().getSegments().size() - 1)
                .getReplicationMode();
    }

    /**
     * Make a copy-on-append copy of a stream.
     *
     * @param source      The UUID of the stream to make a copy of.
     * @param destination The UUID of the destination stream. It must not exist.
     * @return A view
     */
    public IStreamView copy(UUID source, UUID destination, long timestamp) {
        boolean written = false;
        while (!written) {
            TokenResponse tokenResponse =
                    runtime.getSequencerView().nextToken(Collections.singleton(destination), 1);
            if (!tokenResponse.getBackpointerMap().get(destination).equals(-1L)) {
                try {
                    runtime.getAddressSpaceView().fillHole(tokenResponse.getToken());
                } catch (OverwriteException oe) {
                    log.trace("Attempted to hole fill due to already-existing stream but hole filled by other party");
                }
                throw new RuntimeException("Stream already exists!");
            }
            StreamCOWEntry entry = new StreamCOWEntry(source, timestamp);
            try {
                runtime.getAddressSpaceView().write(tokenResponse.getToken(), Collections.singleton(destination),
                        entry, tokenResponse.getBackpointerMap(), tokenResponse.getStreamAddresses());
                written = true;
            } catch (OverwriteException oe) {
                log.debug("hole fill during COW entry append, retrying...");
            }
        }
        return new BackpointerStreamView(runtime, destination);
    }

    /**
     * Write an object to multiple streams, retuning the physical address it
     * was written at.
     * <p>
     * Note: While the completion of this operation guarantees that the append
     * has been persisted, it DOES NOT guarantee that the object has been
     * written to the stream. For example, another client may have deleted
     * the stream.
     *
     * @param object The object to append to the stream.
     * @return The address this
     */
    public long write(Set<UUID> streamIDs, Object object) {
        return acquireAndWrite(streamIDs, object, null);
    }

    public long acquireAndWrite(Set<UUID> streamIDs, Object object,
                                TxResolutionInfo conflictInfo) {
        boolean overwrite = false;
        TokenResponse tokenResponse = null;
        while (true) {
            if (conflictInfo != null) {
                long token;
                if (overwrite) {

                    // on retry, check for conflicts only from the previous
                    // attempt position
                    conflictInfo.setSnapshotTimestamp(tokenResponse.getToken());

                    TokenResponse temp =
                            runtime.getSequencerView().nextToken(streamIDs, 1, conflictInfo);
                    token = temp.getToken();
                    tokenResponse = new TokenResponse(token, temp.getBackpointerMap(), tokenResponse.getStreamAddresses());
                } else {
                    tokenResponse =
                            runtime.getSequencerView().nextToken(streamIDs, 1,  conflictInfo);
                    token = tokenResponse.getToken();
                }
                log.trace("Write[{}]: acquired token = {}, global addr: {}", streamIDs, tokenResponse, token);
                if (token == -1L) {
                    return -1L;
                }
                try {
                    runtime.getAddressSpaceView().write(token, streamIDs,
                            object, tokenResponse.getBackpointerMap(), tokenResponse.getStreamAddresses());
                    return token;
                } catch (ReplexOverwriteException re) {
                    overwrite = false;
                } catch (OverwriteException oe) {
                    overwrite = true;
                    log.debug("Overwrite occurred at {}, retrying.", token);
                }
            } else {
                long token;
                if (overwrite) {
                    TokenResponse temp =
                            runtime.getSequencerView().nextToken(streamIDs, 1);
                    token = temp.getToken();
                    tokenResponse = new TokenResponse(token, temp.getBackpointerMap(), tokenResponse.getStreamAddresses());
                } else {
                    tokenResponse =
                            runtime.getSequencerView().nextToken(streamIDs, 1);
                    token = tokenResponse.getToken();
                }
                log.trace("Write[{}]: acquired token = {}, global addr: {}", streamIDs, tokenResponse, token);
                try {
                    Function<UUID, Object> partialEntryFunction =
                            object instanceof IDivisibleEntry ? ((IDivisibleEntry)object)::divideEntry : null;
                    runtime.getAddressSpaceView().write(token, streamIDs,
                            object, tokenResponse.getBackpointerMap(), tokenResponse.getStreamAddresses(), partialEntryFunction);
                    return token;
                } catch (ReplexOverwriteException re) {
                    overwrite = false;
                } catch (OverwriteException oe) {
                    overwrite = true;
                    log.debug("Overwrite occurred at {}, retrying.", token);
                }
            }
        }
    }
}
