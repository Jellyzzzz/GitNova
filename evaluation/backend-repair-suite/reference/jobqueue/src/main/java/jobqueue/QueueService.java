package jobqueue;

import java.util.ArrayList;
import java.util.List;

public final class QueueService {
    private final List<Job> jobs = new ArrayList<>();

    public void enqueue(Job job) {
        for (Job existing : jobs) if (existing.id.equals(job.id)) throw new IllegalArgumentException("duplicate id");
        jobs.add(job);
    }

    public Job claim(long now, long leaseMillis) {
        if (leaseMillis <= 0) throw new IllegalArgumentException("lease required");
        Job job = new ReadySelector().select(jobs, now);
        if (job == null) return null;
        long until = Math.addExact(now, leaseMillis);
        long token = Math.incrementExact(job.token);
        int attempts = Math.incrementExact(job.attempts);
        job.status = Job.Status.RUNNING;
        job.leaseUntil = until;
        job.token = token;
        job.attempts = attempts;
        return job;
    }

    public List<Job> all() { return List.copyOf(jobs); }
}
