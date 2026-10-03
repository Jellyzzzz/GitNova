package jobqueue;

import java.util.Comparator;
import java.util.List;

public final class ReadySelector {
    public Job select(List<Job> jobs, long now) {
        Comparator<Job> order = Comparator.comparingInt((Job job) -> job.priority).reversed()
                .thenComparingLong(job -> job.createdAt).thenComparing(job -> job.id);
        Job best = null;
        for (Job job : jobs) {
            if (job.status != Job.Status.QUEUED || job.availableAt > now) continue;
            if (best == null || order.compare(job, best) < 0) best = job;
        }
        return best;
    }
}
