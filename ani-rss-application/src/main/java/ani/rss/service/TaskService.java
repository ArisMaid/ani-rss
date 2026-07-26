package ani.rss.service;

import ani.rss.task.BaseTask;
import ani.rss.task.BgmTask;
import ani.rss.task.RenameTask;
import ani.rss.task.RssTask;
import cn.hutool.core.text.NamingCase;
import cn.hutool.extra.spring.SpringUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Vector;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class TaskService {
    public static final AtomicBoolean LOOP = new AtomicBoolean(false);
    public static final List<Thread> THREADS = new Vector<>();
    private final TaskCoordinator coordinator;

    public TaskService(TaskCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    public synchronized void stop() {
        if (!stop(Duration.ofSeconds(30))) {
            throw new IllegalStateException("任务未能在 30 秒内停止");
        }
    }

    public synchronized boolean stop(Duration timeout) {
        LOOP.set(false);
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Thread thread : THREADS) {
            thread.interrupt();
        }
        for (Thread thread : THREADS) {
            try {
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    break;
                }
                thread.join(Math.max(1, Duration.ofNanos(remainingNanos).toMillis()));
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                Thread.currentThread().interrupt();
                break;
            }
        }
        boolean stopped = THREADS.stream().noneMatch(Thread::isAlive);
        if (!stopped) {
            THREADS.stream()
                    .filter(Thread::isAlive)
                    .forEach(thread -> log.error("任务线程停止超时: {}", thread.getName()));
            coordinator.taskStopFailed();
            return false;
        }
        THREADS.clear();
        coordinator.taskStopped();
        return true;
    }

    public synchronized void restart() {
        stop();
        start();
    }

    public synchronized void start() {
        coordinator.requireStartAllowed();
        if (LOOP.get() && !THREADS.isEmpty()) {
            log.warn("任务已经在运行中");
            return;
        }
        LOOP.set(true);

        List<Class<? extends BaseTask>> classList = List.of(RenameTask.class, RssTask.class, BgmTask.class);

        for (Class<? extends BaseTask> aClass : classList) {
            BaseTask task = SpringUtil.getBean(aClass);
            String name = aClass.getSimpleName();
            String threadName = NamingCase.toKebabCase(name);
            THREADS.add(new Thread(() -> task.run(threadName, LOOP), threadName));
        }
        for (Thread thread : THREADS) {
            thread.start();
        }
        coordinator.taskStarted();
    }
}
