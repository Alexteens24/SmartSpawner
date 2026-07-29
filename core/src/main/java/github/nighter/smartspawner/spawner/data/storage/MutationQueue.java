package github.nighter.smartspawner.spawner.data.storage;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Revisioned last-write-wins queue with deletion precedence.
 */
public final class MutationQueue {
    private final AtomicLong revisions = new AtomicLong();
    private final ConcurrentHashMap<String, PendingMutation> pending = new ConcurrentHashMap<>();

    public void modified(SpawnerSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        long revision = revisions.incrementAndGet();
        pending.compute(snapshot.id(), (id, current) -> {
            if (current != null
                    && current.operation() == PendingMutation.Operation.DELETE) {
                return new PendingMutation(
                        PendingMutation.Operation.DELETE, revision, null);
            }
            return new PendingMutation(
                    PendingMutation.Operation.UPSERT, revision, snapshot);
        });
    }

    public void created(SpawnerSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        pending.put(snapshot.id(), new PendingMutation(
                PendingMutation.Operation.UPSERT,
                revisions.incrementAndGet(),
                snapshot));
    }

    public void deleted(String id) {
        if (id == null) {
            return;
        }
        pending.put(id, new PendingMutation(
                PendingMutation.Operation.DELETE,
                revisions.incrementAndGet(),
                null));
    }

    public void modifiedAll(Collection<SpawnerSnapshot> snapshots) {
        if (snapshots != null) {
            snapshots.forEach(this::modified);
        }
    }

    public Map<String, PendingMutation> capture() {
        return new LinkedHashMap<>(pending);
    }

    public void commit(Map<String, PendingMutation> captured) {
        captured.forEach((id, mutation) -> pending.remove(id, mutation));
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public int size() {
        return pending.size();
    }

    public Collection<String> ids() {
        return pending.keySet();
    }
}
