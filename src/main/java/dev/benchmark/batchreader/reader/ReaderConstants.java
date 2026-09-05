package dev.benchmark.batchreader.reader;

public final class ReaderConstants {

    public static final int PAGE_SIZE = 1_000;
    public static final String READY_STATUS = "READY";

    /** 공통 상수만 제공하므로 객체 생성을 막는다. */
    private ReaderConstants() {
    }
}
