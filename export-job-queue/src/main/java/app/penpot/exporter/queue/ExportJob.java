package app.penpot.exporter.queue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * One export request moving through the queue. Callers read its state;
 * only {@link ExportJobQueue} changes it. All state changes go through this
 * object's lock, so a cancel can never race with a worker finishing the job.
 */
public final class ExportJob {

  private final String id;
  private final ExportRequest request;
  private final CountDownLatch finished = new CountDownLatch(1);

  private JobStatus status = JobStatus.PENDING;
  private int attempts;
  private Throwable lastError;
  private byte[] result;
  private Future<?> currentAttempt;

  ExportJob(String id, ExportRequest request) {
    this.id = id;
    this.request = request;
  }

  public String getId() {
    return id;
  }

  public ExportRequest getRequest() {
    return request;
  }

  public synchronized JobStatus getStatus() {
    return status;
  }

  /** Number of times the renderer has been called for this job. */
  public synchronized int getAttempts() {
    return attempts;
  }

  /** The error from the most recent failed attempt, or null. */
  public synchronized Throwable getLastError() {
    return lastError;
  }

  /** The exported bytes once the job has SUCCEEDED, otherwise null. */
  public synchronized byte[] getResult() {
    return result;
  }

  /**
   * Blocks until the job reaches a final status or the wait runs out.
   *
   * @return true if the job finished within the wait
   */
  public boolean awaitCompletion(Duration wait) throws InterruptedException {
    return finished.await(wait.toNanos(), TimeUnit.NANOSECONDS);
  }

  // ---- transitions, called by ExportJobQueue only ----

  synchronized boolean markRunning() {
    if (status != JobStatus.PENDING) {
      return false;
    }
    status = JobStatus.RUNNING;
    return true;
  }

  /**
   * Records the start of an attempt. Returns false, and stops the attempt,
   * if the job was cancelled in the meantime.
   */
  synchronized boolean beginAttempt(Future<?> attempt) {
    if (status != JobStatus.RUNNING) {
      attempt.cancel(true);
      return false;
    }
    attempts++;
    currentAttempt = attempt;
    return true;
  }

  synchronized void recordFailure(Throwable error) {
    lastError = error;
    currentAttempt = null;
  }

  synchronized void succeed(byte[] output) {
    if (status == JobStatus.RUNNING) {
      result = output;
      finish(JobStatus.SUCCEEDED);
    }
  }

  synchronized void fail(Throwable error) {
    if (status == JobStatus.RUNNING) {
      lastError = error;
      finish(JobStatus.FAILED);
    }
  }

  synchronized boolean cancel() {
    if (status.isTerminal()) {
      return false;
    }
    if (currentAttempt != null) {
      currentAttempt.cancel(true);
    }
    finish(JobStatus.CANCELLED);
    return true;
  }

  private void finish(JobStatus finalStatus) {
    status = finalStatus;
    currentAttempt = null;
    finished.countDown();
  }

  @Override
  public synchronized String toString() {
    return "ExportJob[" + id + ", " + request + ", " + status + ", attempts=" + attempts + "]";
  }
}
