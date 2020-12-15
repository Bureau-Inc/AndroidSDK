package id.bureau.util;

import java.util.concurrent.Executors;

public class AsyncUtils {
    public static void scheduleTask(Runnable task) {
        Executors.newSingleThreadExecutor().submit(task);
    }
}
