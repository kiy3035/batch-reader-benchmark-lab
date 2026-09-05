package dev.benchmark.batchreader.experiment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalExperimentDatabaseGuardTest {

    /** localhost와 loopback의 전용 실험 DB URL을 허용하는지 확인한다. */
    @Test
    void acceptsLocalExperimentDatabaseUrls() {
        assertThatCode(() -> LocalExperimentDatabaseGuard.verifyJdbcUrl(
                "jdbc:postgresql://localhost:5432/batch_benchmark")).doesNotThrowAnyException();
        assertThatCode(() -> LocalExperimentDatabaseGuard.verifyJdbcUrl(
                "jdbc:postgresql://127.0.0.1:65432/batch_benchmark")).doesNotThrowAnyException();
        assertThatCode(() -> LocalExperimentDatabaseGuard.verifyJdbcUrl(
                "jdbc:postgresql://[::1]:5432/batch_benchmark")).doesNotThrowAnyException();
    }

    /** 원격 호스트나 다른 DB에 대한 실험성 작업을 거부하는지 확인한다. */
    @Test
    void rejectsRemoteOrUnexpectedDatabaseUrls() {
        assertThatThrownBy(() -> LocalExperimentDatabaseGuard.verifyJdbcUrl(
                "jdbc:postgresql://db.example.com:5432/batch_benchmark"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> LocalExperimentDatabaseGuard.verifyJdbcUrl(
                "jdbc:postgresql://localhost:5432/production"))
                .isInstanceOf(IllegalStateException.class);
    }
}
