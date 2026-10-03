package jobqueue;

public final class RetryPolicy {
    public long delay(int attempt, long base, long cap) {
        if (attempt <= 0 || base <= 0 || cap < base) throw new IllegalArgumentException("invalid retry policy");
        long delay = base;
        for (int index = 1; index < attempt; index++) {
            if (delay >= cap || delay > cap / 2) return cap;
            delay *= 2;
        }
        return delay;
    }
}
