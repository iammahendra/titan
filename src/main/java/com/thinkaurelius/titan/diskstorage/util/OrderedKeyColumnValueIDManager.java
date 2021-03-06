package com.thinkaurelius.titan.diskstorage.util;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import com.google.common.base.Preconditions;
import com.thinkaurelius.titan.diskstorage.*;
import com.thinkaurelius.titan.graphdb.database.idassigner.DefaultIDBlockSizer;
import com.thinkaurelius.titan.graphdb.database.idassigner.IDBlockSizer;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.thinkaurelius.titan.graphdb.configuration.GraphDatabaseConfiguration;

public class OrderedKeyColumnValueIDManager {

    private static final Logger log = LoggerFactory.getLogger(OrderedKeyColumnValueIDManager.class);

    /* This value can't be changed without either
      * corrupting existing ID allocations or taking
      * some additional action to prevent such
      * corruption.
      */
    private static final long BASE_ID = 1;

    private static final ByteBuffer empty = ByteBuffer.allocate(0);



	private final OrderedKeyColumnValueStore store;
	
	private final long lockWaitMS;
	private final int lockRetryCount;
    
    private final int rollbackAttempts = 5;
    private final int rollbackWaitTime = 200;
	
	private final byte[] rid;

    private IDBlockSizer blockSizer;
    private volatile boolean isActive;


//    public OrderedKeyColumnValueIDManager(OrderedKeyColumnValueStore store, byte[] rid, int blockSize, long lockWaitMS, int lockRetryCount) {
	public OrderedKeyColumnValueIDManager(OrderedKeyColumnValueStore store, byte[] rid, Configuration config) {

		this.store = store;
		
		this.rid = rid;
		
		this.blockSizer = new DefaultIDBlockSizer(config.getLong(
                GraphDatabaseConfiguration.IDAUTHORITY_BLOCK_SIZE_KEY,
                GraphDatabaseConfiguration.IDAUTHORITY_BLOCK_SIZE_DEFAULT));
        this.isActive = false;

		this.lockWaitMS = 
				config.getLong(
						GraphDatabaseConfiguration.IDAUTHORITY_WAIT_MS_KEY,
						GraphDatabaseConfiguration.IDAUTHORITY_WAIT_MS_DEFAULT);
		
		this.lockRetryCount = 
				config.getInt(
						GraphDatabaseConfiguration.IDAUTHORITY_RETRY_COUNT_KEY,
						GraphDatabaseConfiguration.IDAUTHORITY_RETRY_COUNT_DEFAULT);
	}

    public synchronized void setIDBlockSizer(IDBlockSizer sizer) {
        if (isActive) throw new IllegalStateException("IDBlockSizer cannot be changed after IDAuthority is in use");
        this.blockSizer=sizer;
    }

	public long[] getIDBlock(int partition) throws StorageException {
        isActive=true;
		long blockSize = blockSizer.getBlockSize(partition);

		for (int retry = 0; retry < lockRetryCount; retry++) {

            try {
                // Read the latest counter values from the store
                ByteBuffer partitionKey = ByteBuffer.allocate(4);
                partitionKey.putInt(partition).rewind();
                List<Entry> blocks = store.getSlice(partitionKey, empty, empty, 5, null);
                if (blocks==null) throw new TemporaryStorageException("Could not read from storage");

                long latest = BASE_ID - blockSize;

                for (Entry e : blocks) {
                    long counterVal = getBlockValue(e.getColumn());
                    if (latest < counterVal) {
                        latest = counterVal;
                    }
                }

                // calculate the start (inclusive) and end (exclusive) of the allocation we're about to attempt
                long nextStart = latest + blockSize;
                long nextEnd = nextStart + blockSize;

                ByteBuffer target = getBlockApplication(nextStart);


                // attempt to write our claim on the next id block
                boolean success = false;
                try {
                    long before = System.currentTimeMillis();
                    store.mutate(partitionKey, Arrays.asList(new Entry(target, empty)), null, null);
                    long after = System.currentTimeMillis();

                    if (lockWaitMS < after - before) {
                        throw new TemporaryStorageException("Wrote claim for id block ["+nextStart+", "+nextEnd+") in "+(after-before)+" ms => too slow, threshold is: "+lockWaitMS);
                    } else {

                        assert 0 != target.remaining();
                        ByteBuffer[] slice = getBlockSlice(nextStart);

                        /* At this point we've written our claim on [nextStart, nextEnd),
                         * but we haven't yet guaranteed the absence of a contending claim on
                         * the same id block from another machine
                         */

                        while (true) {
                            // Wait until lockWaitMS has passed since our claim
                            final long sinceLock = System.currentTimeMillis() - after;
                            if (sinceLock >= lockWaitMS) {
                                break;
                            } else {
                                try {
                                    Thread.sleep(lockWaitMS - sinceLock);
                                } catch (InterruptedException e) {
                                    throw new PermanentLockingException("Interupted while waiting for lock confirmation",e);
                                }
                            }
                        }

                        // Read all id allocation claims on this partition, for the counter value we're claiming
                        blocks = store.getSlice(partitionKey, slice[0], slice[1], null);
                        if (blocks==null) throw new TemporaryStorageException("Could not read from storage");
                        if (blocks.isEmpty()) throw new PermanentStorageException("It seems there is a race-condition in the block application. " +
                                "If you have multiple Titan instances running on one physical machine, ensure that they have unique machine ids");

                        /* If our claim is the lexicographically first one, then our claim
                         * is the most senior one and we own this id block
                         */
                        if (target.equals(blocks.get(0).getColumn())) {

                            long result[] = new long[2];
                            result[0] = nextStart;
                            result[1] = nextEnd;

                            if (log.isDebugEnabled()) {
                                log.debug("Acquired ID block [{},{}) on partition {} (my rid is {})",
                                        new Object[] { nextStart, nextEnd, partition, new String(Hex.encodeHex(rid)) });
                            }

                            success = true;
                            return result;
                        } else {
                            // Another claimant beat us to this id block -- try again.
                            log.debug("Failed to acquire ID block [{},{}) (another host claimed it first)", nextStart, nextEnd);
                        }
                    }
                } finally {
                    if (!success) {
                        //Delete claim to not pollute id space
                        try {
                            for (int attempt=0;attempt<rollbackAttempts;attempt++) {
                                store.mutate(partitionKey, null, Arrays.asList(target), null);
                                break;
                            }
                        } catch (StorageException e) {
                            log.warn("Storage exception while deleting old block application - retrying in {} ms: {}",rollbackWaitTime,e);
                            try {
                                Thread.sleep(rollbackWaitTime);
                            } catch (InterruptedException ex) {
                                throw new PermanentLockingException("Interrupted while waiting for old id block removal retry",ex);
                            }
                        }
                    }
                }
            } catch (TemporaryStorageException e) {
                log.warn("Temporary storage exception while acquiring id block - retrying in {} ms: {}",lockWaitMS,e);
                try {
                    Thread.sleep(lockWaitMS);
                } catch (InterruptedException ex) {
                    throw new PermanentLockingException("Interrupted while waiting for id block acquisition retry",ex);
                }
            }
		}
		
		throw new TemporaryLockingException("Exceeded timeout count ["+lockRetryCount+"] when attempting to allocate id block");
    }
	
    private final ByteBuffer[] getBlockSlice(long blockValue) {
        ByteBuffer[] slice = new ByteBuffer[2];
        slice[0] = ByteBuffer.allocate(16);
        slice[1] = ByteBuffer.allocate(16);
        slice[0].putLong(-blockValue).putLong(0).rewind();
        slice[1].putLong(-blockValue).putLong(-1).rewind();
        return slice;
    }
    
	private final ByteBuffer getBlockApplication(long blockValue) {
		ByteBuffer bb = ByteBuffer.allocate(
				8 // counter long
				+ 8 // time in ms
				+ rid.length);
		
		bb.putLong(-blockValue).putLong(System.currentTimeMillis()).put(rid);
		bb.rewind();
		return bb;
	}
    
    private final long getBlockValue(ByteBuffer column) {
        return -column.getLong();
    }
    
}
