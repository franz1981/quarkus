package org.jboss.resteasy.reactive.server.vertx;

import java.util.ArrayDeque;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

final class AppendBuffer {
    private final ByteBufAllocator allocator;
    private final int capacity;
    private ByteBuf buffer;
    private ArrayDeque<ByteBuf> otherBuffers;
    private int size;
    private int minChunkSize;

    private AppendBuffer(ByteBufAllocator allocator, int capacity, int minChunkSize) {
        this.allocator = allocator;
        this.capacity = capacity;
        this.minChunkSize = Math.min(minChunkSize, capacity);
    }

    public static AppendBuffer withEagerChunks(ByteBufAllocator allocator, int capacity) {
        return new AppendBuffer(allocator, capacity, capacity);
    }

    public static AppendBuffer withExactChunks(ByteBufAllocator allocator, int capacity) {
        return new AppendBuffer(allocator, capacity, 0);
    }

    public static AppendBuffer withMinChunks(ByteBufAllocator allocator, int capacity, int minChunkSize) {
        return new AppendBuffer(allocator, capacity, minChunkSize);
    }

    private ByteBuf lastBuffer() {
        if (otherBuffers == null || otherBuffers.isEmpty()) {
            return buffer;
        }
        return otherBuffers.peekLast();
    }

    /**
     * It returns how many bytes have been appended<br>
     * If the return value is different from {@code len}, is it required to invoke {@link #flushBatch}
     * that would refill the available capacity till {@link #capacity()}
     */
    public int append(byte[] bytes, int off, int len) {
        int alreadyWritten = 0;
        if (minChunkSize > 0) {
            var lastBuffer = lastBuffer();
            if (lastBuffer != null) {
                int availableOnLast = lastBuffer.writableBytes();
                if (availableOnLast > 0) {
                    int toWrite = Math.min(len, availableOnLast);
                    lastBuffer.writeBytes(bytes, off, toWrite);
                    size += toWrite;
                    len -= toWrite;
                    // we stop if there's no more to append
                    if (len == 0) {
                        return toWrite;
                    }
                    off += toWrite;
                    alreadyWritten = toWrite;
                }
            }
        }
        final int availableCapacity = capacity - size;
        if (availableCapacity == 0) {
            return alreadyWritten;
        }
        // we can still write some
        int toWrite = Math.min(len, availableCapacity);
        final int chunkCapacity;
        if (minChunkSize > 0) {
            // Cannot allocate less the minChunkSize, till the limit of capacity left
            chunkCapacity = Math.min(Math.max(minChunkSize, toWrite), availableCapacity);
        } else {
            chunkCapacity = toWrite;
        }
        var tmpBuf = allocator.directBuffer(chunkCapacity);
        tmpBuf.writeBytes(bytes, off, toWrite);
        if (buffer == null) {
            buffer = tmpBuf;
        } else {
            try {
                if (otherBuffers == null) {
                    otherBuffers = new ArrayDeque<>();
                }
                otherBuffers.add(tmpBuf);
            } catch (Throwable t) {
                size -= alreadyWritten;
                tmpBuf.release();
                throw t;
            }
        }
        size += toWrite;
        return toWrite + alreadyWritten;
    }

    public ByteBuf flushBatch() {
        var firstBuf = buffer;
        if (firstBuf == null) {
            return null;
        }
        var others = otherBuffers;
        if (others == null || others.isEmpty()) {
            size = 0;
            buffer = null;
            // super fast-path
            return firstBuf;
        }
        var batch = allocator.compositeDirectBuffer(1 + others.size());
        try {
            // we unset this first in case addComponent would fail and will be released
            // same applies for size
            buffer = null;
            final int firstBytes = firstBuf.readableBytes();
            size -= firstBytes;
            batch.addComponent(true, 0, firstBuf);
            for (int i = 0, othersCount = others.size(); i < othersCount; i++) {
                var curr = others.poll();
                // we have already been able to remove it from the queue!
                size -= curr.readableBytes();
                // if addComponent fail, it takes care of releasing curr and throwing the exception:
                // we lose curr data, but not the rest into others
                batch.addComponent(true, 1 + i, curr);
            }
            assert size == 0;
            // net-safe barrier to keep this to work in case of bugs
            size = 0;
            return batch;
        } catch (Throwable anyError) {
            // we cannot reuse what's fallen already into batch :"(
            batch.release();
            throw anyError;
        }
    }

    public int capacity() {
        return capacity;
    }

    public int availableCapacity() {
        return capacity - size;
    }

}
