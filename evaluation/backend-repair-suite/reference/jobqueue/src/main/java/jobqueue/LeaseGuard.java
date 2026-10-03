package jobqueue;

public final class LeaseGuard {
    public boolean complete(Job job, long token, long now) {
        if (job.status != Job.Status.RUNNING || token != job.token || now >= job.leaseUntil) return false;
        job.status = Job.Status.COMPLETED;
        return true;
    }
}
