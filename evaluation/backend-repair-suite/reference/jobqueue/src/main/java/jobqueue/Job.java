package jobqueue;

public final class Job {
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }
    public final String id;
    public final int priority;
    public final long createdAt;
    public Status status = Status.QUEUED;
    public long availableAt;
    public long leaseUntil;
    public long token;
    public int attempts;

    public Job(String id, int priority, long createdAt) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id required");
        this.id = id;
        this.priority = priority;
        this.createdAt = createdAt;
        this.availableAt = createdAt;
    }
}
