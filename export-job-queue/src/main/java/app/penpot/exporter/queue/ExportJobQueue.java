package app.penpot.exporter.queue;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Accepts export requests and runs them with a renderer, at most
 * {@code maxConcurrent} at a time. Jobs that can't start yet wait in FIFO
 * order.
 *
 * <p>Each job gets up to {@code 1 + maxRetries} attempts. An attempt fails
 * if the renderer throws or runs longer than {@code timeout}; a timed-out
 * attempt is interrupted. A job whose attempts all fail is marked FAILED and
 * its slot goes to the next waiting job.
 *
 * <p>Each running job uses two threads: a worker that holds the slot and
 * enforces the timeout, and a render thread that calls the renderer. A
 * renderer that ignores interruption keeps its render thread busy after a
 * timeout or cancel, but it no longer holds a slot.
 */
public final class ExportJobQueue implements AutoCloseable {

  private final Renderer renderer;
  private final int maxConcurrent;
  private final int maxRetries;
  private final Duration timeout;

  private final Map<String, ExportJob> jobs = new ConcurrentHashMap<>();
  private final ExecutorService workers;
  private final ExecutorService renderThreads;
  private boolean closed;

  /**
   * @param renderer does the export work
   * @param maxConcurrent most jobs that may run at once; at least 1
   * @param maxRetries extra attempts after the first failure; 0 or more
   * @param timeout longest a single attempt may run; must be positive
   * @throws IllegalArgumentException if any argument is null or out of range
   */
  public ExportJobQueue(Renderer renderer, int maxConcurrent, int maxRetries, Duration timeout) {
    if (renderer == null) {
      throw new IllegalArgumentException("renderer must not be null");
    }
    if (maxConcurrent < 1) {
      throw new IllegalArgumentException("maxConcurrent must be at least 1");
    }
    if (maxRetries < 0) {
      throw new IllegalArgumentException("maxRetries must not be negative");
    }
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    this.renderer = renderer;
    this.maxConcurrent = maxConcurrent;
    this.maxRetries = maxRetries;
    this.timeout = timeout;
    // A fixed pool hands out tasks in submission order, which gives FIFO
    // and caps the number of running jobs at maxConcurrent.
    this.workers = Executors.newFixedThreadPool(maxConcurrent, daemonThreads("export-worker"));
    this.renderThreads = Executors.newCachedThreadPool(daemonThreads("export-render"));
  }

  /**
   * Adds a request to the queue.
   *
   * @return the new job, with a unique ID and status PENDING
   * @throws IllegalArgumentException if {@code request} is null
   * @throws IllegalStateException if the queue has been closed
   */
  public synchronized ExportJob submit(ExportRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request must not be null");
    }
    if (closed) {
      throw new IllegalStateException("queue is closed");
    }
    ExportJob job = new ExportJob(UUID.randomUUID().toString(), request);
    jobs.put(job.getId(), job);
    workers.execute(() -> run(job));
    return job;
  }

  /** Looks up a job by ID. Unknown or null IDs give an empty result. */
  public Optional<ExportJob> getJob(String jobId) {
    return jobId == null ? Optional.empty() : Optional.ofNullable(jobs.get(jobId));
  }

  /**
   * Cancels a PENDING or RUNNING job. A running attempt is interrupted.
   *
   * @return true if the job was cancelled, false if it had already finished
   * @throws IllegalArgumentException if {@code jobId} is null, blank or
   *     unknown
   */
  public boolean cancel(String jobId) {
    if (jobId == null || jobId.isBlank()) {
      throw new IllegalArgumentException("jobId must not be null or blank");
    }
    ExportJob job = jobs.get(jobId);
    if (job == null) {
      throw new IllegalArgumentException("unknown job: " + jobId);
    }
    return job.cancel();
  }

  /** Number of jobs with status RUNNING. Never more than maxConcurrent. */
  public int runningCount() {
    return countWithStatus(JobStatus.RUNNING);
  }

  /** Number of jobs with status PENDING. */
  public int pendingCount() {
    return countWithStatus(JobStatus.PENDING);
  }

  public int getMaxConcurrent() {
    return maxConcurrent;
  }

  public int getMaxRetries() {
    return maxRetries;
  }

  public Duration getTimeout() {
    return timeout;
  }

  /** Cancels every unfinished job and stops all threads. */
  @Override
  public void close() {
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
    }
    jobs.values().forEach(ExportJob::cancel);
    workers.shutdownNow();
    renderThreads.shutdownNow();
  }

  private void run(ExportJob job) {
    if (!job.markRunning()) {
      return; // cancelled while waiting
    }
    while (true) {
      Future<byte[]> attempt = renderThreads.submit(() -> renderer.render(job.getRequest()));
      if (!job.beginAttempt(attempt)) {
        return; // cancelled between attempts
      }
      Throwable error;
      try {
        job.succeed(attempt.get(timeout.toNanos(), TimeUnit.NANOSECONDS));
        return;
      } catch (CancellationException e) {
        return; // the job was cancelled; it already holds its final status
      } catch (TimeoutException e) {
        attempt.cancel(true);
        error = new TimeoutException("export attempt timed out after " + timeout);
      } catch (ExecutionException e) {
        error = e.getCause();
      } catch (InterruptedException e) {
        // Only happens when close() stops the workers.
        attempt.cancel(true);
        Thread.currentThread().interrupt();
        return;
      }
      if (job.getAttempts() > maxRetries) {
        job.fail(error);
        return;
      }
      job.recordFailure(error);
    }
  }

  private int countWithStatus(JobStatus status) {
    return (int) jobs.values().stream().filter(j -> j.getStatus() == status).count();
  }

  private static ThreadFactory daemonThreads(String prefix) {
    AtomicInteger n = new AtomicInteger();
    return task -> {
      Thread t = new Thread(task, prefix + "-" + n.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }
}
