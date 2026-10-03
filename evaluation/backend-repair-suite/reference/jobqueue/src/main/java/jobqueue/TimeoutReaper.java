package jobqueue;

import java.util.List;

public final class TimeoutReaper {
    public int reap(List<Job> jobs, long now, int maxAttempts, long base, long cap) {
        if (maxAttempts <= 0) throw new IllegalArgumentException("maxAttempts required");
        int changed = 0;
        for (Job job : jobs) {
            if (job.status != Job.Status.RUNNING || now < job.leaseUntil) continue;
            if (job.attempts >= maxAttempts) {
                job.status = Job.Status.FAILED;
            } else {
                long availableAt = Math.addExact(now, new RetryPolicy().delay(job.attempts, base, cap));
                job.status = Job.Status.QUEUED;
                job.availableAt = availableAt;
            }
            changed++;
        }
        return changed;
    }
}
