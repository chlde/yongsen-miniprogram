package com.yongsen.training;

/** Dispatches only persisted job IDs. Implementations must never include credentials in errors. */
public interface BackgroundInvoker {
    void invoke(String jobId);
}
