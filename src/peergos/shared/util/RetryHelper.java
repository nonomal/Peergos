package peergos.shared.util;

import peergos.shared.storage.*;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class RetryHelper {

    private static final Random random = new Random(1);
    private static final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);

    private static <V> void retryAfter(Supplier<CompletableFuture<V>> method, int milliseconds) {
        executor.schedule(method::get, milliseconds, TimeUnit.MILLISECONDS);
    }

    private static int jitter(int minMilliseconds, int rangeMilliseconds) {
        return minMilliseconds + random.nextInt(rangeMilliseconds);
    }

    public static <V> CompletableFuture<V> runWithRetry(Supplier<CompletableFuture<V>> f) {
        return runWithRetry(3, f);
    }

    public static <V> CompletableFuture<V> runWithRetry(int maxAttempts, Supplier<CompletableFuture<V>> f) {
        return recurse(maxAttempts, maxAttempts, f);
    }

    private static <V> CompletableFuture<V> recurse(int maxAttempts, int retriesLeft, Supplier<CompletableFuture<V>> f) {
        CompletableFuture<V> res = new CompletableFuture<>();
        try {
            f.get()
                    .thenAccept(res::complete)
                    .exceptionally(e -> {
                        System.out.println("kevinhere");
                        if (retriesLeft == 1) {
                            res.completeExceptionally(e);
                        } else if (e instanceof StorageQuotaExceededException) {
                            res.completeExceptionally(e);
                        } else if (e instanceof HttpFileNotFoundException) {
                            res.completeExceptionally(e);
                        } else {
                            retryAfter(() -> recurse(maxAttempts, retriesLeft - 1, f)
                                            .thenAccept(res::complete)
                                            .exceptionally(t -> {
                                                res.completeExceptionally(t);
                                                return null;
                                            }),
                                    jitter((maxAttempts + 1 - retriesLeft) * 1000, 500));
                        }
                        return null;
                    });
        } catch (Throwable t) {
            res.completeExceptionally(t);
        }
        return res;
    }
}
