package com.yuyin.asr.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuyin.asr.config.AsrProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * 投递失败的磁盘持久化队列：内存重试耗尽后落盘，进程重启自动续传，保证「内容不丢」。
 * 文件名 {epochMillis(20位)}_{seq}_{batchId}.json，按名字典序即时间序重投。
 */
@Component
public class DeliveryQueue {

    private static final Logger log = LoggerFactory.getLogger(DeliveryQueue.class);

    private final AsrProperties.Delivery cfg;
    private final ObjectMapper mapper;
    private final Path dir;
    private final AtomicLong seq = new AtomicLong(0);
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "asr-delivery-queue");
                t.setDaemon(true);
                return t;
            });

    /** 单条投递逻辑，由 AnalysisDeliveryClient 注入；返回 true 表示投递成功。 */
    private volatile Predicate<BatchPayload> sender = p -> false;

    public DeliveryQueue(AsrProperties props, ObjectMapper mapper) {
        this.cfg = props.getDelivery();
        this.mapper = mapper;
        this.dir = Paths.get(cfg.getQueueDir());
    }

    public void setSender(Predicate<BatchPayload> sender) {
        this.sender = sender;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("create delivery queue dir failed: {}", dir, e);
        }
        long period = Math.max(1000, cfg.getQueueFlushMs());
        scheduler.scheduleWithFixedDelay(this::flush, period, period, TimeUnit.MILLISECONDS);
        log.info("delivery queue ready at {} (flush every {}ms)", dir.toAbsolutePath(), period);
    }

    /** 落盘一条待重投的批（原子写：先写 .tmp 再 rename）。 */
    public void enqueue(BatchPayload payload) {
        String name = String.format("%020d_%010d_%s.json",
                System.currentTimeMillis(), seq.incrementAndGet(), safe(payload.batchId));
        Path tmp = dir.resolve(name + ".tmp");
        Path target = dir.resolve(name);
        try {
            Files.write(tmp, mapper.writeValueAsBytes(payload));
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
            log.warn("batch {} persisted to disk queue for later retry", payload.batchId);
        } catch (IOException e) {
            log.error("persist batch {} failed", payload.batchId, e);
        }
    }

    /** 扫描磁盘队列按时间序重投，成功即删除。 */
    private void flush() {
        List<Path> files = listOrdered();
        for (Path f : files) {
            BatchPayload payload;
            try {
                payload = mapper.readValue(Files.readAllBytes(f), BatchPayload.class);
            } catch (IOException e) {
                log.error("read queued file {} failed, skip", f.getFileName(), e);
                continue;
            }
            boolean ok;
            try {
                ok = sender.test(payload);
            } catch (Exception e) {
                ok = false;
            }
            if (ok) {
                try { Files.deleteIfExists(f); } catch (IOException ignored) {}
                log.info("re-delivered queued batch {}", payload.batchId);
            } else {
                // 仍失败：保留文件，下个周期再试（避免饥饿，停止本轮）
                break;
            }
        }
    }

    private List<Path> listOrdered() {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> list = new ArrayList<>();
            s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(list::add);
            list.sort(Comparator.comparing(p -> p.getFileName().toString()));
            return list;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String safe(String s) {
        return s == null ? "na" : s.replaceAll("[^a-zA-Z0-9_-]", "");
    }
}
