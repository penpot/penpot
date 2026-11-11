package app.penpot.exporter.queue;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.stubbing.Answer;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ExportJobQueueTest {

  private static final Duration WAIT = Duration.ofSeconds(5);
  private static final Duration LONG_TIMEOUT = Duration.ofSeconds(30);
  private static final byte[] BYTES = {1, 2, 3, 4};

  private Renderer renderer;
  private ExportJobQueue queue;
  /** Released in tearDown so no renderer thread stays blocked. */
  private final CountDownLatch release = new CountDownLatch(1);

  @BeforeEach
  void setUp() {
    renderer = mock(Renderer.class);
  }

  @AfterEach
  void tearDown() {
    release.countDown();
    if (queue != null) {
      queue.close();
    }
  }

  // ---------------------------------------------------------------- helpers

  private ExportJobQueue newQueue(int maxConcurrent, int maxRetries, Duration timeout) {
    queue = new ExportJobQueue(renderer, maxConcurrent, maxRetries, timeout);
    return queue;
  }

  private static ExportRequest req(String fileId) {
    return new ExportRequest(fileId, ExportFormat.PNG);
  }

  /** Signals {@code started}, then blocks until {@link #release} opens. */
  private Answer<byte[]> blockUntilReleased(CountDownLatch started) {
    return inv -> {
      started.countDown();
      release.await();
      return BYTES;
    };
  }

  /** Sleeps far past any test timeout; reports interruption through the latch. */
  private static Answer<byte[]> hang(CountDownLatch interrupted) {
    return inv -> {
      try {
        Thread.sleep(60_000);
      } catch (InterruptedException e) {
        interrupted.countDown();
        throw e;
      }
      return BYTES;
    };
  }

  private static void awaitTrue(BooleanSupplier condition, String message)
      throws InterruptedException {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        fail("Timed out waiting: " + message);
      }
      Thread.sleep(10);
    }
  }

  private static void assertCompletes(ExportJob job) throws InterruptedException {
    assertTrue(job.awaitCompletion(WAIT), "job " + job.getId() + " did not finish in time");
  }

  // ------------------------------------------------------------ constructor

  @Nested
  @DisplayName("Constructor validation")
  class ConstructorValidation {

    @Test
    void storesSettings() {
      newQueue(3, 2, Duration.ofSeconds(7));
      assertEquals(3, queue.getMaxConcurrent());
      assertEquals(2, queue.getMaxRetries());
      assertEquals(Duration.ofSeconds(7), queue.getTimeout());
      assertEquals(0, queue.runningCount());
      assertEquals(0, queue.pendingCount());
    }

    @Test
    void acceptsMinimumValidValues() {
      assertDoesNotThrow(() -> newQueue(1, 0, Duration.ofNanos(1)));
    }

    @Test
    void rejectsNullRenderer() {
      assertThrows(IllegalArgumentException.class,
          () -> new ExportJobQueue(null, 1, 0, LONG_TIMEOUT));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void rejectsMaxConcurrentBelowOne(int maxConcurrent) {
      assertThrows(IllegalArgumentException.class,
          () -> new ExportJobQueue(renderer, maxConcurrent, 0, LONG_TIMEOUT));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void rejectsNegativeMaxRetries(int maxRetries) {
      assertThrows(IllegalArgumentException.class,
          () -> new ExportJobQueue(renderer, 1, maxRetries, LONG_TIMEOUT));
    }

    @Test
    void rejectsNullTimeout() {
      assertThrows(IllegalArgumentException.class,
          () -> new ExportJobQueue(renderer, 1, 0, null));
    }

    @Test
    void rejectsZeroTimeout() {
      assertThrows(IllegalArgumentException.class,
          () -> new ExportJobQueue(renderer, 1, 0, Duration.ZERO));
    }

    @Test
    void rejectsNegativeTimeout() {
      assertThrows(IllegalArgumentException.class,
          () -> new ExportJobQueue(renderer, 1, 0, Duration.ofMillis(-1)));
    }
  }

  // ------------------------------------------------------ 1. submit / IDs

  @Nested
  @DisplayName("1. Submit: unique IDs and PENDING start")
  class Submit {

    @Test
    void submittedJobGetsUuidIdAndKeepsRequest() throws Exception {
      when(renderer.render(any())).thenReturn(BYTES);
      newQueue(1, 0, LONG_TIMEOUT);
      ExportRequest request = req("file-1");

      ExportJob job = queue.submit(request);

      assertNotNull(job);
      assertNotNull(job.getId());
      assertDoesNotThrow(() -> UUID.fromString(job.getId()));
      assertEquals(request, job.getRequest());
    }

    @Test
    void jobWaitingForSlotIsPendingWithNoProgress() throws Exception {
      CountDownLatch started = new CountDownLatch(1);
      when(renderer.render(any())).thenAnswer(blockUntilReleased(started));
      newQueue(1, 0, LONG_TIMEOUT);
      queue.submit(req("blocker"));
      assertTrue(started.await(5, TimeUnit.SECONDS));

      ExportJob job = queue.submit(req("waiting"));

      assertEquals(JobStatus.PENDING, job.getStatus());
      assertFalse(job.getStatus().isTerminal());
      assertEquals(0, job.getAttempts());
      assertNull(job.getLastError());
      assertNull(job.getResult());
      assertEquals(1, queue.pendingCount());
    }

    @Test
    void manySubmittedJobsGetDistinctIds() throws Exception {
      CountDownLatch started = new CountDownLatch(1);
      when(renderer.render(any())).thenAnswer(blockUntilReleased(started));
      newQueue(1, 0, LONG_TIMEOUT);

      Set<String> ids = new HashSet<>();
      for (int i = 0; i < 200; i++) {
        ids.add(queue.submit(req("file-" + i)).getId());
      }

      assertEquals(200, ids.size());
    }

    @Test
    void identicalRequestsStillGetDistinctIds() throws Exception {
      when(renderer.render(any())).thenReturn(BYTES);
      newQueue(2, 0, LONG_TIMEOUT);
      ExportRequest request = req("same");

      ExportJob a = queue.submit(request);
      ExportJob b = queue.submit(request);

      assertFalse(a.getId().equals(b.getId()));
    }

    @Test
    void getJobFindsSubmittedJob() throws Exception {
      when(renderer.render(any())).thenReturn(BYTES);
      newQueue(1, 0, LONG_TIMEOUT);
      ExportJob job = queue.submit(req("file-1"));

      Optional<ExportJob> found = queue.getJob(job.getId());

      assertTrue(found.isPresent());
      assertEquals(job.getId(), found.get().getId());
      assertEquals(job.getRequest(), found.get().getRequest());
    }

    @Test
    void getJobReturnsEmptyForNullOrUnknownId() {
      newQueue(1, 0, LONG_TIMEOUT);
      assertTrue(queue.getJob(null).isEmpty());
      assertTrue(queue.getJob(UUID.randomUUID().toString()).isEmpty());
      assertTrue(queue.getJob("").isEmpty());
    }

    @Test
    void submitRejectsNullRequest() {
      newQueue(1, 0, LONG_TIMEOUT);
      assertThrows(IllegalArgumentException.class, () -> queue.submit(null));
      assertEquals(0, queue.pendingCount());
    }

    @Test
    void submitAfterCloseThrowsIllegalState() {
      newQueue(1, 0, LONG_TIMEOUT);
      queue.close();
      assertThrows(IllegalStateException.class, () -> queue.submit(req("late")));
    }

    @Test
    void successfulJobStoresResultAndCallsRendererOnce() throws Exception {
      when(renderer.render(any())).thenReturn(BYTES);
      newQueue(1, 3, LONG_TIMEOUT);
      ExportRequest request = req("ok");

      ExportJob job = queue.submit(request);
      assertCompletes(job);

      assertEquals(JobStatus.SUCCEEDED, job.getStatus());
      assertArrayEquals(BYTES, job.getResult());
      assertEquals(1, job.getAttempts());
      assertNull(job.getLastError());
      verify(renderer, times(1)).render(request);
    }

    @Test
    void emptyResultStillCountsAsSuccess() throws Exception {
      when(renderer.render(any())).thenReturn(new byte[0]);
      newQueue(1, 0, LONG_TIMEOUT);

      ExportJob job = queue.submit(req("empty"));
      assertCompletes(job);

      assertEquals(JobStatus.SUCCEEDED, job.getStatus());
      assertArrayEquals(new byte[0], job.getResult());
    }

    @Test
    void awaitCompletionReturnsFalseWhileJobStillRuns() throws Exception {
      CountDownLatch started = new CountDownLatch(1);
      when(renderer.render(any())).thenAnswer(blockUntilReleased(started));
      newQueue(1, 0, LONG_TIMEOUT);

      ExportJob job = queue.submit(req("slow"));
      assertTrue(started.await(5, TimeUnit.SECONDS));

      assertFalse(job.awaitCompletion(Duration.ofMillis(100)));
      assertEquals(JobStatus.RUNNING, job.getStatus());
    }
  }

  // -------------------------------------------- 2. concurrency and FIFO

  @Nested
  @DisplayName("2. Concurrency limit and FIFO order")
  class ConcurrencyAndOrder {

    @Test
    void neverRunsMoreThanMaxConcurrent() throws Exception {
      AtomicInteger active = new AtomicInteger();
      AtomicInteger maxActive = new AtomicInteger();
      when(renderer.render(any())).thenAnswer(inv -> {
        int now = active.incrementAndGet();
        maxActive.accumulateAndGet(now, Math::max);
        try {
          release.await();
          return BYTES;
        } finally {
          active.decrementAndGet();
        }
      });
      newQueue(2, 0, LONG_TIMEOUT);

      List<ExportJob> jobs = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        jobs.add(queue.submit(req("file-" + i)));
      }

      awaitTrue(() -> active.get() == 2, "two renders to start");
      awaitTrue(() -> queue.runningCount() == 2, "runningCount to reach 2");
      Thread.sleep(200); // give a third job a chance to (wrongly) start
      assertEquals(2, active.get());
      assertEquals(2, queue.runningCount());
      assertEquals(4, queue.pendingCount());

      release.countDown();
      for (ExportJob job : jobs) {
        assertCompletes(job);
        assertEquals(JobStatus.SUCCEEDED, job.getStatus());
      }
      assertTrue(maxActive.get() <= 2, "max active was " + maxActive.get());
      assertEquals(0, queue.runningCount());
      assertEquals(0, queue.pendingCount());
    }

    @Test
    void singleSlotRunsJobsOneAtATime() throws Exception {
      AtomicInteger active = new AtomicInteger();
      AtomicInteger maxActive = new AtomicInteger();
      when(renderer.render(any())).thenAnswer(inv -> {
        maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
        try {
          Thread.sleep(20);
          return BYTES;
        } finally {
          active.decrementAndGet();
        }
      });
      newQueue(1, 0, LONG_TIMEOUT);

      List<ExportJob> jobs = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        jobs.add(queue.submit(req("file-" + i)));
      }
      for (ExportJob job : jobs) {
        assertCompletes(job);
      }

      assertEquals(1, maxActive.get());
    }

    @Test
    void waitingJobsStartInSubmissionOrder() throws Exception {
      List<String> order = Collections.synchronizedList(new ArrayList<>());
      CountDownLatch blockerStarted = new CountDownLatch(1);
      when(renderer.render(any())).thenAnswer(inv -> {
        ExportRequest r = inv.getArgument(0);
        order.add(r.fileId());
        if (r.fileId().equals("blocker")) {
          blockerStarted.countDown();
          release.await();
        }
        return BYTES;
      });
      newQueue(1, 0, LONG_TIMEOUT);

      ExportJob blocker = queue.submit(req("blocker"));
      assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));
      List<ExportJob> waiting = new ArrayList<>();
      for (String id : List.of("A", "B", "C", "D", "E")) {
        waiting.add(queue.submit(req(id)));
      }
      assertEquals(5, queue.pendingCount());

      release.countDown();
      assertCompletes(blocker);
      for (ExportJob job : waiting) {
        assertCompletes(job);
      }

      assertEquals(List.of("blocker", "A", "B", "C", "D", "E"), order);
    }

    @Test
    void freedSlotIsReusedByNextWaitingJob() throws Exception {
      CountDownLatch firstStarted = new CountDownLatch(1);
      ExportRequest first = req("first");
      ExportRequest second = req("second");
      when(renderer.render(first)).thenAnswer(blockUntilReleased(firstStarted));
      when(renderer.render(second)).thenReturn(BYTES);
      newQueue(1, 0, LONG_TIMEOUT);

      ExportJob a = queue.submit(first);
      ExportJob b = queue.submit(second);
      assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
      assertEquals(JobStatus.PENDING, b.getStatus());

      release.countDown();
      assertCompletes(a);
      assertCompletes(b);
      assertEquals(JobStatus.SUCCEEDED, b.getStatus());
    }

    @Test
    void failedJobDoesNotBlockLaterJobs() throws Exception {
      ExportRequest bad = req("bad");
      ExportRequest good = req("good");
      when(renderer.render(bad)).thenThrow(new IOException("boom"));
      when(renderer.render(good)).thenReturn(BYTES);
      newQueue(1, 0, LONG_TIMEOUT);

      ExportJob failing = queue.submit(bad);
      ExportJob ok = queue.submit(good);

      assertCompletes(failing);
      assertCompletes(ok);
      assertEquals(JobStatus.FAILED, failing.getStatus());
      assertEquals(JobStatus.SUCCEEDED, ok.getStatus());
      assertEquals(0, queue.runningCount());
    }
  }

  // ----------------------------------------------------------- 3. retries

  @Nested
  @DisplayName("3. Retries up to the limit, then FAILED")
  class Retries {

    @Test
    void alwaysFailingJobUsesAllAttemptsThenFails() throws Exception {
      IOException error = new IOException("render failed");
      when(renderer.render(any())).thenThrow(error);
      newQueue(1, 2, LONG_TIMEOUT);
      ExportRequest request = req("broken");

      ExportJob job = queue.submit(request);
      assertCompletes(job);

      assertEquals(JobStatus.FAILED, job.getStatus());
      assertEquals(3, job.getAttempts());
      assertSame(error, job.getLastError());
      assertNull(job.getResult());
      verify(renderer, after(200).times(3)).render(request);
    }

    @Test
    void zeroRetriesMeansSingleAttempt() throws Exception {
      when(renderer.render(any())).thenThrow(new IllegalStateException("nope"));
      newQueue(1, 0, LONG_TIMEOUT);

      ExportJob job = queue.submit(req("once"));
      assertCompletes(job);

      assertEquals(JobStatus.FAILED, job.getStatus());
      assertEquals(1, job.getAttempts());
      assertInstanceOf(IllegalStateException.class, job.getLastError());
      verify(renderer, after(200).times(1)).render(any());
    }

    @Test
    void lastErrorHoldsErrorFromFinalAttempt() throws Exception {
      IOException first = new IOException("first");
      RuntimeException second = new RuntimeException("second");
      when(renderer.render(any())).thenThrow(first).thenThrow(second);
      newQueue(1, 1, LONG_TIMEOUT);

      ExportJob job = queue.submit(req("two-errors"));
      assertCompletes(job);

      assertEquals(JobStatus.FAILED, job.getStatus());
      assertEquals(2, job.getAttempts());
      assertSame(second, job.getLastError());
    }

    @Test
    void jobSucceedsOnLastAllowedAttempt() throws Exception {
      when(renderer.render(any()))
          .thenThrow(new IOException("1"))
          .thenThrow(new IOException("2"))
          .thenReturn(BYTES);
      newQueue(1, 2, LONG_TIMEOUT);

      ExportJob job = queue.submit(req("flaky"));
      assertCompletes(job);

      assertEquals(JobStatus.SUCCEEDED, job.getStatus());
      assertEquals(3, job.getAttempts());
      assertArrayEquals(BYTES, job.getResult());
      verify(renderer, times(3)).render(any());
    }

    @Test
    void successStopsFurtherRetries() throws Exception {
      when(renderer.render(any())).thenThrow(new IOException("1")).thenReturn(BYTES);
      newQueue(1, 5, LONG_TIMEOUT);

      ExportJob job = queue.submit(req("recovers"));
      assertCompletes(job);

      assertEquals(JobStatus.SUCCEEDED, job.getStatus());
      assertEquals(2, job.getAttempts());
      verify(renderer, after(200).times(2)).render(any());
    }

    @Test
    void oneMoreFailureThanRetriesLeadsToFailedEvenIfLaterCallWouldSucceed()
        throws Exception {
      when(renderer.render(any()))
          .thenThrow(new IOException("1"))
          .thenThrow(new IOException("2"))
          .thenReturn(BYTES);
      newQueue(1, 1, LONG_TIMEOUT);

      ExportJob job = queue.submit(req("too-flaky"));
      assertCompletes(job);

      assertEquals(JobStatus.FAILED, job.getStatus());
      assertEquals(2, job.getAttempts());
      assertNull(job.getResult());
      verify(renderer, after(200).times(2)).render(any());
    }

    @Test
    void checkedAndUncheckedErrorsAreBothRetried() throws Exception {
      when(renderer.render(any()))
          .thenThrow(new IOException("checked"))
          .thenThrow(new RuntimeException("unchecked"))
          .thenReturn(BYTES);
      newQueue(1, 2, LONG_TIMEOUT);

      ExportJob job = queue.submit(req("mixed"));
      assertCompletes(job);

      assertEquals(JobStatus.SUCCEEDED, job.getStatus());
      assertEquals(3, job.getAttempts());
    }

    @Test
    void failedJobCannotBeCancelled() throws Exception {
      when(renderer.render(any())).thenThrow(new IOException("x"));
      newQueue(1, 0, LONG_TIMEOUT);

      ExportJob job = queue.submit(req("dead"));
      assertCompletes(job);

      assertFalse(queue.cancel(job.getId()));
      assertEquals(JobStatus.FAILED, job.getStatus());
    }
  }

  // ----------------------------------------------------------- 4. timeout

  @Nested
  @DisplayName("4. Timeout stops attempts and counts as failure")
  class Timeouts {

    @Test
    void attemptPastTimeoutFailsWithTimeoutException() throws Exception {
      CountDownLatch interrupted = new CountDownLatch(1);
      when(renderer.render(any())).thenAnswer(hang(interrupted));
      newQueue(1, 0, Duration.ofMillis(200));

      ExportJob job = queue.submit(req("slow"));
      assertCompletes(job);

      assertEquals(JobStatus.FAILED, job.getStatus());
      assertEquals(1, job.getAttempts());
      assertInstanceOf(TimeoutException.class, job.getLastError());
      assertNull(job.getResult());
    }

    @Test
    void timedOutAttemptIsInterrupted() throws Exception {
      CountDownLatch interrupted = new CountDownLatch(1);
      when(renderer.render(any())).thenAnswer(hang(interrupted));
      newQueue(1, 0, Duration.ofMillis(200));

      queue.submit(req("slow"));

      assertTrue(interrupted.await(5, TimeUnit.SECONDS),
          "renderer thread was not interrupted after timeout");
    }

    @Test
    void timeoutsFollowRetryRules() throws Exception {
      CountDownLatch interrupted = new CountDownLatch(3);
      when(renderer.render(any())).thenAnswer(hang(interrupted));
      newQueue(1, 2, Duration.ofMillis(150));

      ExportJob job = queue.submit(req("always-slow"));
      assertCompletes(job);

      assertEquals(JobStatus.FAILED, job.getStatus());
      assertEquals(3, job.getAttempts());
      assertInstanceOf(TimeoutException.class, job.getLastError());
      verify(renderer, after(200).times(3)).render(any());
      assertTrue(interrupted.await(5, TimeUnit.SECONDS));
    }

    @Test
    void retryAfterTimeoutCanSucceed() throws Exception {
      CountDownLatch interrupted = new CountDownLatch(1);
      when(renderer.render(any())).thenAnswer(hang(interrupted)).thenReturn(BYTES);
      newQueue(1, 1, Duration.ofMillis(200));

      ExportJob job = queue.submit(req("slow-then-fast"));
      assertCompletes(job);

      assertEquals(JobStatus.SUCCEEDED, job.getStatus());
      assertEquals(2, job.getAttempts());
      assertArrayEquals(BYTES, job.getResult());
    }

    @Test
    void timeoutAppliesPerAttemptNotPerJob() throws Exception {
      // Each attempt takes ~300 ms under a 1 s timeout; together they exceed 1 s.
      Answer<byte[]> slowFailure = inv -> {
        Thread.sleep(300);
        throw new IOException("slow failure");
      };
      Answer<byte[]> slowSuccess = inv -> {
        Thread.sleep(300);
        return BYTES;
      };
      when(renderer.render(any()))
          .thenAnswer(slowFailure)
          .thenAnswer(slowFailure)
          .thenAnswer(slowFailure)
          .thenAnswer(slowSuccess);
      newQueue(1, 3, Duration.ofSeconds(1));

      ExportJob job = queue.submit(req("per-attempt"));
      assertCompletes(job);

      assertEquals(JobStatus.SUCCEEDED, job.getStatus());
      assertEquals(4, job.getAttempts());
    }

    @Test
    void fastRenderUnderTimeoutSucceeds() throws Exception {
      when(renderer.render(any())).thenReturn(BYTES);
      newQueue(1, 0, Duration.ofMillis(500));

      ExportJob job = queue.submit(req("fast"));
      assertCompletes(job);

      assertEquals(JobStatus.SUCCEEDED, job.getStatus());
      assertNull(job.getLastError());
    }

    @Test
    void timedOutJobFreesItsSlot() throws Exception {
      CountDownLatch interrupted = new CountDownLatch(1);
      ExportRequest slow = req("slow");
      ExportRequest fast = req("fast");
      when(renderer.render(slow)).thenAnswer(hang(interrupted));
      when(renderer.render(fast)).thenReturn(BYTES);
      newQueue(1, 0, Duration.ofMillis(200));

      ExportJob a = queue.submit(slow);
      ExportJob b = queue.submit(fast);

      assertCompletes(a);
      assertCompletes(b);
      assertEquals(JobStatus.FAILED, a.getStatus());
      assertEquals(JobStatus.SUCCEEDED, b.getStatus());
    }
  }

  // ------------------------------------------------- 5. cancel and close

  @Nested
  @DisplayName("5. Cancel and input validation")
  class Cancel {

    @Test
    void cancelPendingJobMarksItCancelledAndSkipsRenderer() throws Exception {
      CountDownLatch blockerStarted = new CountDownLatch(1);
      ExportRequest blockerReq = req("blocker");
      ExportRequest pendingReq = req("pending");
      ExportRequest laterReq = req("later");
      when(renderer.render(blockerReq)).thenAnswer(blockUntilReleased(blockerStarted));
      when(renderer.render(pendingReq)).thenReturn(BYTES);
      when(renderer.render(laterReq)).thenReturn(BYTES);
      newQueue(1, 3, LONG_TIMEOUT);

      ExportJob blocker = queue.submit(blockerReq);
      assertTrue(blockerStarted.await(5, TimeUnit.SECONDS));
      ExportJob pending = queue.submit(pendingReq);
      assertEquals(JobStatus.PENDING, pending.getStatus());

      assertTrue(queue.cancel(pending.getId()));
      assertEquals(JobStatus.CANCELLED, pending.getStatus());
      assertEquals(0, queue.pendingCount());
      assertTrue(pending.awaitCompletion(WAIT));

      ExportJob later = queue.submit(laterReq);
      release.countDown();
      assertCompletes(blocker);
      assertCompletes(later);

      assertEquals(JobStatus.CANCELLED, pending.getStatus());
      assertEquals(0, pending.getAttempts());
      assertNull(pending.getResult());
      verify(renderer, never()).render(pendingReq);
    }

    @Test
    void cancelRunningJobStopsItAndDoesNotRetry() throws Exception {
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch interrupted = new CountDownLatch(1);
      when(renderer.render(any())).thenAnswer(inv -> {
        started.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          interrupted.countDown();
          throw e;
        }
        return BYTES;
      });
      newQueue(1, 3, LONG_TIMEOUT);

      ExportJob job = queue.submit(req("running"));
      assertTrue(started.await(5, TimeUnit.SECONDS));
      awaitTrue(() -> job.getStatus() == JobStatus.RUNNING, "job to be RUNNING");

      assertTrue(queue.cancel(job.getId()));
      assertEquals(JobStatus.CANCELLED, job.getStatus());
      assertTrue(job.awaitCompletion(WAIT));
      assertTrue(interrupted.await(5, TimeUnit.SECONDS), "running render was not stopped");

      verify(renderer, after(300).times(1)).render(any());
      assertEquals(JobStatus.CANCELLED, job.getStatus());
      assertNull(job.getResult());
      assertEquals(0, queue.runningCount());
    }

    @Test
    void cancelRunningJobFreesSlotForNextJob() throws Exception {
      CountDownLatch started = new CountDownLatch(1);
      ExportRequest first = req("first");
      ExportRequest second = req("second");
      when(renderer.render(first)).thenAnswer(blockUntilReleased(started));
      when(renderer.render(second)).thenReturn(BYTES);
      newQueue(1, 0, LONG_TIMEOUT);

      ExportJob a = queue.submit(first);
      ExportJob b = queue.submit(second);
      assertTrue(started.await(5, TimeUnit.SECONDS));

      assertTrue(queue.cancel(a.getId()));
      assertCompletes(b);
      assertEquals(JobStatus.SUCCEEDED, b.getStatus());
    }

    @Test
    void cancelSucceededJobReturnsFalse() throws Exception {
      when(renderer.render(any())).thenReturn(BYTES);
      newQueue(1, 0, LONG_TIMEOUT);

      ExportJob job = queue.submit(req("done"));
      assertCompletes(job);

      assertFalse(queue.cancel(job.getId()));
      assertEquals(JobStatus.SUCCEEDED, job.getStatus());
      assertArrayEquals(BYTES, job.getResult());
    }

    @Test
    void cancelTwiceReturnsFalseSecondTime() throws Exception {
      CountDownLatch started = new CountDownLatch(1);
      when(renderer.render(any())).thenAnswer(blockUntilReleased(started));
      newQueue(1, 0, LONG_TIMEOUT);
      queue.submit(req("blocker"));
      assertTrue(started.await(5, TimeUnit.SECONDS));
      ExportJob pending = queue.submit(req("pending"));

      assertTrue(queue.cancel(pending.getId()));
      assertFalse(queue.cancel(pending.getId()));
      assertEquals(JobStatus.CANCELLED, pending.getStatus());
    }

    @Test
    void cancelRejectsNullId() {
      newQueue(1, 0, LONG_TIMEOUT);
      assertThrows(IllegalArgumentException.class, () -> queue.cancel(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    void cancelRejectsBlankId(String id) {
      newQueue(1, 0, LONG_TIMEOUT);
      assertThrows(IllegalArgumentException.class, () -> queue.cancel(id));
    }

    @Test
    void cancelRejectsUnknownId() {
      newQueue(1, 0, LONG_TIMEOUT);
      assertThrows(IllegalArgumentException.class,
          () -> queue.cancel(UUID.randomUUID().toString()));
    }

    @Test
    void cancelRejectsIdThatLooksLikeKnownIdWithWhitespace() throws Exception {
      when(renderer.render(any())).thenReturn(BYTES);
      newQueue(1, 0, LONG_TIMEOUT);
      ExportJob job = queue.submit(req("x"));

      assertThrows(IllegalArgumentException.class, () -> queue.cancel(" " + job.getId()));
    }
  }

  @Nested
  @DisplayName("5. Close")
  class Close {

    @Test
    void closeCancelsRunningAndPendingJobs() throws Exception {
      CountDownLatch started = new CountDownLatch(1);
      ExportRequest runningReq = req("running");
      ExportRequest pendingReq = req("pending");
      when(renderer.render(runningReq)).thenAnswer(blockUntilReleased(started));
      when(renderer.render(pendingReq)).thenReturn(BYTES);
      newQueue(1, 3, LONG_TIMEOUT);

      ExportJob running = queue.submit(runningReq);
      assertTrue(started.await(5, TimeUnit.SECONDS));
      ExportJob pending = queue.submit(pendingReq);

      queue.close();

      assertCompletes(running);
      assertCompletes(pending);
      assertEquals(JobStatus.CANCELLED, running.getStatus());
      assertEquals(JobStatus.CANCELLED, pending.getStatus());
      verify(renderer, after(300).never()).render(pendingReq);
      verify(renderer, times(1)).render(runningReq);
    }

    @Test
    void closeLeavesFinishedJobsUnchanged() throws Exception {
      when(renderer.render(any())).thenReturn(BYTES);
      newQueue(1, 0, LONG_TIMEOUT);
      ExportJob done = queue.submit(req("done"));
      assertCompletes(done);

      queue.close();

      assertEquals(JobStatus.SUCCEEDED, done.getStatus());
      assertArrayEquals(BYTES, done.getResult());
    }

    @Test
    void closeTwiceDoesNothing() throws Exception {
      CountDownLatch started = new CountDownLatch(1);
      when(renderer.render(any())).thenAnswer(blockUntilReleased(started));
      newQueue(1, 0, LONG_TIMEOUT);
      ExportJob job = queue.submit(req("x"));
      assertTrue(started.await(5, TimeUnit.SECONDS));

      queue.close();
      assertCompletes(job);
      assertDoesNotThrow(() -> queue.close());

      assertEquals(JobStatus.CANCELLED, job.getStatus());
      assertThrows(IllegalStateException.class, () -> queue.submit(req("y")));
    }

    @Test
    void closeOnEmptyQueueIsSafe() {
      newQueue(1, 0, LONG_TIMEOUT);
      assertDoesNotThrow(() -> queue.close());
      assertEquals(0, queue.runningCount());
      assertEquals(0, queue.pendingCount());
    }

    @Test
    void submitAfterCloseStillRejectsNullWithSomeException() {
      newQueue(1, 0, LONG_TIMEOUT);
      queue.close();
      // Either check may run first; the spec only promises that it fails.
      assertThrows(RuntimeException.class, () -> queue.submit(null));
    }
  }
}
