package dev.benchmark.batchreader.benchmark;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class OldGenSamplerTest {

    /** 반복 샘플 중 가장 큰 사용량만 peak로 보존하는지 확인한다. */
    @Test
    void capturesMaximumSample() throws Exception {
        AtomicLong value = new AtomicLong(100L);
        OldGenSampler sampler = new OldGenSampler(5L, () -> value.getAndAdd(100L), null);
        sampler.start();
        Thread.sleep(25L);
        sampler.close();

        assertThat(sampler.peakBytes()).isPresent();
        assertThat(sampler.peakBytes().getAsLong()).isGreaterThanOrEqualTo(100L);
        assertThat(sampler.measurementStatus()).isEqualTo("MEASURED");
    }

    /** G1 pool이 없을 때 0 대신 명시적인 N/A를 반환하는지 확인한다. */
    @Test
    void reportsUnavailablePool() {
        OldGenSampler sampler = new OldGenSampler(5L, null, "missing pool");
        sampler.start();
        sampler.close();

        assertThat(sampler.peakBytes()).isEmpty();
        assertThat(sampler.measurementStatus()).isEqualTo("N/A: missing pool");
    }
}
