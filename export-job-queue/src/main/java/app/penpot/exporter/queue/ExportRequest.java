package app.penpot.exporter.queue;

/**
 * What to export: a Penpot file and the target format.
 *
 * @throws IllegalArgumentException if {@code fileId} is null or blank, or
 *     {@code format} is null
 */
public record ExportRequest(String fileId, ExportFormat format) {

  public ExportRequest {
    if (fileId == null || fileId.isBlank()) {
      throw new IllegalArgumentException("fileId must not be null or blank");
    }
    if (format == null) {
      throw new IllegalArgumentException("format must not be null");
    }
  }
}
