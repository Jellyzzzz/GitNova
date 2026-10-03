package jobqueue;

public final class CancellationService {
    public boolean cancel(Job job) {
        if (job.status != Job.Status.QUEUED && job.status != Job.Status.RUNNING) return false;
        job.status = Job.Status.CANCELLED;
        return true;
    }
}
