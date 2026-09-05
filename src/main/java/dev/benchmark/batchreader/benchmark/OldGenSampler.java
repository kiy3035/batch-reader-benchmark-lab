package dev.benchmark.batchreader.benchmark;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

public class OldGenSampler implements AutoCloseable {

    private final long intervalMillis;
    private final LongSupplier usedBytes;
    private final String unavailableReason;
    private final AtomicLong peakBytes = new AtomicLong(-1L);
    private ScheduledExecutorService executor;

    /** 플랫폼의 G1 Old Gen pool을 찾아 sampler를 만든다. */
    public static OldGenSampler forG1(long intervalMillis) {
        MemoryPoolMXBean pool = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(bean -> "G1 Old Gen".equals(bean.getName()))
                .findFirst()
                .orElse(null);
        if (pool == null) {
            return new OldGenSampler(intervalMillis, null, "G1 Old Gen memory pool을 찾지 못했습니다.");
        }
        return new OldGenSampler(intervalMillis, () -> pool.getUsage().getUsed(), null);
    }

    /** 테스트 가능한 사용량 공급자와 샘플링 간격으로 sampler를 만든다. */
    public OldGenSampler(long intervalMillis, LongSupplier usedBytes, String unavailableReason) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("샘플링 간격은 1ms 이상이어야 합니다.");
        }
        this.intervalMillis = intervalMillis;
        this.usedBytes = usedBytes;
        this.unavailableReason = unavailableReason;
    }

    /** 고정 간격으로 Old Gen 사용량 샘플링을 시작한다. */
    public void start() {
        if (usedBytes == null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "old-gen-sampler");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleAtFixedRate(this::sample, 0L, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** 현재까지 관측한 최대 Old Gen bytes를 반환한다. */
    public OptionalLong peakBytes() {
        long peak = peakBytes.get();
        return peak < 0 ? OptionalLong.empty() : OptionalLong.of(peak);
    }

    /** 측정 불가 사유 또는 정상 측정 상태를 반환한다. */
    public String measurementStatus() {
        return unavailableReason == null ? "MEASURED" : "N/A: " + unavailableReason;
    }

    /** sampler thread를 중지하고 마지막 샘플 완료를 기다린다. */
    @Override
    public void close() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            executor.awaitTermination(Math.max(100L, intervalMillis * 2), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** 현재 사용량을 읽어 기존 peak보다 큰 경우 원자적으로 갱신한다. */
    private void sample() {
        long current = usedBytes.getAsLong();
        peakBytes.accumulateAndGet(current, Math::max);
    }
}
