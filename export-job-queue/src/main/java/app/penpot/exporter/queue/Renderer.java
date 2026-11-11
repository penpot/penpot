package app.penpot.exporter.queue;

/**
 * Does the actual export work. The queue calls it once per attempt, on a
 * separate thread. A renderer should respond to thread interruption, since
 * the queue interrupts it on timeout or cancel.
 */
@FunctionalInterface
public interface Renderer {

  /**
   * Renders the request and returns the exported bytes.
   *
   * @throws Exception on any failure; the queue counts it as a failed attempt
   */
  byte[] render(ExportRequest request) throws Exception;
}
