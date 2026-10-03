package jobqueue;

import java.math.BigInteger;
import java.util.List;
import static testing.Checks.*;

public final class Oracle {
    public static void main(String[] args) {
        for (int sample = 1; sample <= 24; sample++) {
            final int n = sample;
            test("ready/order-and-filter/" + sample, () -> {
                Job low = new Job("low", -n, 0);
                Job older = new Job("z", n, 1);
                Job newer = new Job("a", n, 2);
                Job tied = new Job("b", n, 1);
                Job cancelled = new Job("cancelled", Integer.MAX_VALUE, 0);
                cancelled.status = Job.Status.CANCELLED;
                equal(tied, new ReadySelector().select(List.of(low, older, newer, tied, cancelled), 10));
                equal(older, new ReadySelector().select(List.of(low, older, newer), 10));
                equal(null, new ReadySelector().select(List.of(cancelled), 10));
            });
            test("lease/reclaim-fence/" + sample, () -> {
                QueueService queue = new QueueService();
                queue.enqueue(new Job("j", n, 0));
                Job job = queue.claim(0, 10);
                long oldToken = job.token;
                equal(false, new LeaseGuard().complete(job, oldToken, 10));
                equal(Job.Status.RUNNING, job.status);
                new TimeoutReaper().reap(queue.all(), 10, 4, 5, 50);
                equal(null, queue.claim(14, 10));
                equal(job, queue.claim(15, 10));
                equal(2, job.attempts);
                equal(oldToken + 1, job.token);
                equal(false, new LeaseGuard().complete(job, oldToken, 16));
                equal(Job.Status.RUNNING, job.status);
                equal(true, new LeaseGuard().complete(job, job.token, 16));
            });
            test("reaper/terminal-and-idempotent/" + sample, () -> {
                Job job = new Job("j", 1, 0);
                job.status = Job.Status.RUNNING;
                job.attempts = n;
                job.leaseUntil = 30;
                equal(0, new TimeoutReaper().reap(List.of(job), 29, n, 1, 100));
                equal(1, new TimeoutReaper().reap(List.of(job), 30, n, 1, 100));
                equal(Job.Status.FAILED, job.status);
                equal(0, new TimeoutReaper().reap(List.of(job), 31, n, 1, 100));
                equal(n, job.attempts);
            });
            test("reaper/next-available-time/" + sample, () -> {
                Job job = new Job("j", 1, 0);
                job.status = Job.Status.RUNNING;
                job.attempts = 1;
                job.leaseUntil = 30;
                equal(1, new TimeoutReaper().reap(List.of(job), 30, 3, n, n * 8L));
                equal(Job.Status.QUEUED, job.status);
                equal(30L + n, job.availableAt);
                equal(0, new TimeoutReaper().reap(List.of(job), 100, 3, n, n * 8L));
                equal(30L + n, job.availableAt);
            });
        }
        for (int attempt : new int[]{1, 2, 3, 8, 31, 32, 63, 64, 65, 100, 1000}) {
            for (long base : new long[]{1, 7, 123456789, Long.MAX_VALUE / 3}) {
                test("retry/saturating/" + attempt + "/" + base, () -> {
                    long cap = Long.MAX_VALUE - 10;
                    long expected = BigInteger.valueOf(base).shiftLeft(attempt - 1).min(BigInteger.valueOf(cap)).longValueExact();
                    equal(expected, new RetryPolicy().delay(attempt, base, cap));
                    equal(base, new RetryPolicy().delay(attempt, base, base));
                });
            }
        }
        for (Job.Status status : Job.Status.values()) {
            test("cancel/" + status, () -> {
                Job job = new Job("j", 1, 0);
                job.status = status;
                job.token = 3;
                job.attempts = 2;
                job.leaseUntil = 20;
                boolean cancellable = status == Job.Status.QUEUED || status == Job.Status.RUNNING;
                equal(cancellable, new CancellationService().cancel(job));
                equal(cancellable ? Job.Status.CANCELLED : status, job.status);
                equal(false, new CancellationService().cancel(job));
                equal(false, new LeaseGuard().complete(job, 3, 10));
                equal(0, new TimeoutReaper().reap(List.of(job), 100, 3, 1, 10));
                equal(2, job.attempts);
            });
        }
        test("retry/invalid", () -> {
            rejects(() -> new RetryPolicy().delay(0, 1, 10));
            rejects(() -> new RetryPolicy().delay(1, 0, 10));
            rejects(() -> new RetryPolicy().delay(1, 20, 10));
        });
        finish("ORACLE");
    }
}
