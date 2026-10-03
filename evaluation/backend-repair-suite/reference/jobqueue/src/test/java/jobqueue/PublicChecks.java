package jobqueue;

import java.util.List;
import static testing.Checks.*;

public final class PublicChecks {
    public static void main(String[] args) {
        test("ready/priority", () -> {
            Job low = new Job("low", 1, 0);
            Job high = new Job("high", 5, 1);
            equal(high, new ReadySelector().select(List.of(low, high), 10));
        });
        test("ready/future", () -> equal(null, new ReadySelector().select(List.of(new Job("future", 9, 20)), 10)));
        test("lease/stale-token", () -> {
            QueueService queue = new QueueService();
            queue.enqueue(new Job("job", 1, 0));
            Job job = queue.claim(10, 10);
            equal(false, new LeaseGuard().complete(job, job.token - 1, 11));
            equal(Job.Status.RUNNING, job.status);
        });
        test("retry/exponential", () -> equal(40L, new RetryPolicy().delay(3, 10, 100)));
        test("reaper/exhaustion", () -> {
            QueueService queue = new QueueService();
            queue.enqueue(new Job("job", 1, 0));
            Job job = queue.claim(0, 10);
            equal(1, new TimeoutReaper().reap(queue.all(), 10, 1, 10, 100));
            equal(Job.Status.FAILED, job.status);
        });
        test("cancel/completed", () -> {
            Job job = new Job("job", 1, 0);
            job.status = Job.Status.COMPLETED;
            equal(false, new CancellationService().cancel(job));
            equal(Job.Status.COMPLETED, job.status);
        });
        test("cancel/late-completion", () -> {
            QueueService queue = new QueueService();
            queue.enqueue(new Job("job", 1, 0));
            Job job = queue.claim(0, 10);
            equal(true, new CancellationService().cancel(job));
            equal(false, new LeaseGuard().complete(job, job.token, 1));
        });
        finish("PUBLIC");
    }
}
