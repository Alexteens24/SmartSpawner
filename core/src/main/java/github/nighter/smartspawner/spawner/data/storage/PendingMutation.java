package github.nighter.smartspawner.spawner.data.storage;

public record PendingMutation(
        Operation operation,
        long revision,
        SpawnerSnapshot snapshot
) {
    public enum Operation {
        UPSERT,
        DELETE
    }
}
