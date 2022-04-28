package ru.mail.polis.arturgaleev;

import jdk.incubator.foreign.MemorySegment;
import ru.mail.polis.Config;
import ru.mail.polis.Dao;
import ru.mail.polis.Entry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.util.concurrent.Executors.newSingleThreadExecutor;

public class MemorySegmentDao implements Dao<MemorySegment, Entry<MemorySegment>> {

    private final ReadWriteLock memoryLock;
    private final Config config;
    private final AtomicLong newFileId;
    private final ExecutorService flushExecutor;
    private final ExecutorService compactExecutor;
    private volatile State state;
    private Future<?> compactFuture;
    private Future<?> flushFuture;

    public MemorySegmentDao(Config config) throws IOException {
        this.config = config;
        state = new State(false, new Memory(), null, new DBReader(config.basePath()));
        memoryLock = new ReentrantReadWriteLock();
        newFileId = new AtomicLong(state.storage.getBiggestFileId() + 1);

        flushExecutor = newSingleThreadExecutor(runnable -> new Thread(runnable, "DAO: flush thread "));
        compactExecutor = newSingleThreadExecutor(runnable -> new Thread(runnable, "DAO: compact thread "));
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        State currentState = state;
        Iterator<Entry<MemorySegment>> dataBaseIterator = currentState.memory.get(from, to);

        if (currentState.storage.hasNoReaders() && (state.flushing == null || state.flushing.isEmpty())) {
            return new TombstoneRemoverIterator(new PriorityPeekingIterator<>(1, dataBaseIterator));
        } else {
            Iterator<Entry<MemorySegment>> flushingIterator;
            if (currentState.flushing == null) {
                flushingIterator = Collections.emptyIterator();
            } else {
                flushingIterator = currentState.flushing.get(from, to);
            }

            return new MergeIterator<>(
                    List.of(
                            new PriorityPeekingIterator<>(0, currentState.storage.get(from, to)),
                            new PriorityPeekingIterator<>(1, flushingIterator),
                            new PriorityPeekingIterator<>(2, dataBaseIterator)
                    ),
                    MemorySegmentComparator.INSTANCE
            );
        }
    }

    @Override
    public Entry<MemorySegment> get(MemorySegment key) {
        State currentState = state;

        Entry<MemorySegment> entry = currentState.memory.get(key);
        if (entry == null) {
            if (!(currentState.flushing == null)) {
                entry = currentState.flushing.get(key);
            }
        }
        if (!(entry == null)) {
            return entry.value() == null ? null : entry;
        }
        // TODO storage may be closed
        return currentState.storage.get(key);
    }

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        long byteSize;
        memoryLock.readLock().lock();
        try {
            byteSize = state.memory.upsert(entry);
        } finally {
            memoryLock.readLock().unlock();
        }
        if (byteSize > config.flushThresholdBytes()
                && state.memory.byteSize.compareAndSet(byteSize, 0)) {
            try {
                flush();
            } catch (IOException e) {
                // To many flushes
                throw new UncheckedIOException(e);
            }
        }
    }

    @Override
    public void compact() throws IOException {
        if (state.storage.hasNoReaders()) {
            return;
        }
        compactFuture = compactExecutor.submit(() -> {
            try {
                Path compactionPath = getNewFileName();
                if (flushFuture != null) {
                    try {
                        flushFuture.get();
                    } catch (InterruptedException | ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                }
                State currentState = this.state;
                try (FileDBWriter writer = new FileDBWriter(compactionPath)) {
                    if (writer.writeIterable(() -> currentState.storage.get(null, null))) {
                        memoryLock.writeLock().lock();
                        try {
                            currentState.storage.clear();
                            this.state = state.afterCompact(new DBReader(config.basePath()));
                        } finally {
                            memoryLock.writeLock().unlock();
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } finally {
                compactFuture = null;
            }
        });
    }

    @Override
    public synchronized void flush() throws IOException {
        State currentState = this.state;
        if (currentState.memory.isEmpty()) {
            return;
        }

        // We shouldn't always wait for writeLock to compare

        // if this memory being flushed by hand/auto flush
        if (currentState.memory == state.flushing) {
            return;
        }
        if (currentState.isFlushing || flushFuture != null) {
            // To many flushes. Caused by hand flush
            throw new IOException();
        }
        // If no synchronized then some flush after ten years of sleeping may wake up and write old info
        memoryLock.writeLock().lock();
        try {
            // if this memory being flushed by hand/auto flush
            if (currentState.memory == state.flushing) {
                return;
            }
            if (state.isFlushing || flushFuture != null) {
                throw new IOException();
            }
            this.state = currentState.prepareForFlush();
        } finally {
            memoryLock.writeLock().unlock();
        }

        flushFuture = flushExecutor.submit(this::bgFlush);
    }

    @Override
    public void close() throws IOException {
        awaitFuture(compactFuture);
        compactExecutor.shutdown();

        awaitFuture(flushFuture);

        flush();
        awaitFuture(flushFuture);
        flushExecutor.shutdown();
    }

    private void awaitFuture(Future<?> future) {
        if (future != null) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // Logger...
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private Path getNewFileName() {
        return config.basePath().resolve(newFileId.getAndIncrement() + ".txt");
    }

    private void bgFlush() {
        try {
            Path filePath = getNewFileName();
            try (FileDBWriter writer = new FileDBWriter(filePath)) {
                writer.writeIterable(state.flushing.values());
                memoryLock.writeLock().lock();
                try {
                    this.state = state.afterFlush(new DBReader(config.basePath()));
                } finally {
                    memoryLock.writeLock().unlock();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            flushFuture = null;
        }
    }
}
