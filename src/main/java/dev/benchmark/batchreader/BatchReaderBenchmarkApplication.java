package dev.benchmark.batchreader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BatchReaderBenchmarkApplication {

    /** 애플리케이션과 설정된 Spring Batch Job을 시작한다. */
    public static void main(String[] args) {
        SpringApplication.run(BatchReaderBenchmarkApplication.class, args);
    }
}
