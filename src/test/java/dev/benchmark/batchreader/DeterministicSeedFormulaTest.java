package dev.benchmark.batchreader;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicSeedFormulaTest {

    /** 같은 ID를 입력하면 항상 같은 합성 금액이 계산되는지 확인한다. */
    @Test
    void calculatesStableAmountFromId() {
        assertThat(amountFor(1234)).isEqualByComparingTo("456.58");
        assertThat(amountFor(1234)).isEqualByComparingTo(amountFor(1234));
    }

    /** SQL seed와 같은 정수 연산으로 ID별 금액을 계산한다. */
    private BigDecimal amountFor(long id) {
        return BigDecimal.valueOf((id * 37) % 1_000_000, 2);
    }
}
