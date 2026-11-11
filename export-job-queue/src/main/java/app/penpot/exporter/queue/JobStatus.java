package app.penpot.exporter.queue;

/** Life cycle of an export job. SUCCEEDED, FAILED and CANCELLED are final. */
public enum JobStatus {
  PENDING,
  RUNNING,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED;
  }
}
