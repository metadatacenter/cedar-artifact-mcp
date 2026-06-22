package org.metadatacenter.artifacts.mcp.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Filesystem helpers for the artifact file tools ({@code artifact_from_file}, {@code
 * artifact_to_file}, {@code convert_artifact_file}). Paths must be absolute — a localhost MCP's
 * working directory is rarely what a user expects, so a relative path would resolve somewhere
 * surprising. Reads and writes are UTF-8.
 */
final class ArtifactFiles
{
  private ArtifactFiles() {}

  /**
   * Resolve a required absolute path argument.
   *
   * @throws IllegalArgumentException if blank or not absolute
   */
  static Path requireAbsolute(String value, String argName)
  {
    if (value == null || value.isBlank())
      throw new IllegalArgumentException(argName + " is required and must not be blank");
    Path path = Path.of(value);
    if (!path.isAbsolute())
      throw new IllegalArgumentException(
          argName + " must be an absolute path (got '" + value + "')");
    return path;
  }

  /** Read a file's text, with clear errors for a missing path or a directory. */
  static String read(Path path) throws IOException
  {
    if (!Files.exists(path))
      throw new NoSuchFileException(path.toString());
    if (Files.isDirectory(path))
      throw new IOException(path + " is a directory, not a file");
    return Files.readString(path);
  }

  /** Write text to a file (UTF-8), creating parent directories and overwriting any existing file. */
  static long write(Path path, String content) throws IOException
  {
    Path parent = path.getParent();
    if (parent != null)
      Files.createDirectories(parent);
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    Files.write(path, bytes);
    return bytes.length;
  }

  /**
   * Whether to render YAML (vs JSON), from an explicit {@code format} argument or, when absent,
   * the path's extension ({@code .json} → JSON; anything else → YAML, the default).
   *
   * @throws IllegalArgumentException if an explicit format is neither yaml/yml nor json
   */
  static boolean wantsYaml(String formatArg, Path path)
  {
    if (formatArg != null && !formatArg.isBlank()) {
      String format = formatArg.trim().toLowerCase();
      if (format.equals("yaml") || format.equals("yml"))
        return true;
      if (format.equals("json"))
        return false;
      throw new IllegalArgumentException("format must be 'yaml' or 'json' (got '" + formatArg + "')");
    }
    String name = path.getFileName().toString().toLowerCase();
    return !name.endsWith(".json");
  }

  /** The optional {@code compact} flag (YAML only), defaulting to false (expanded, lossless). */
  static boolean compactFlag(Object raw)
  {
    if (raw == null)
      return false;
    if (raw instanceof Boolean b)
      return b;
    throw new IllegalArgumentException(
        "compact must be a boolean (got " + raw.getClass().getSimpleName() + ")");
  }

  /**
   * Reject {@code compact: true} paired with JSON output. Compaction drops provenance to produce
   * the lean YAML exchange form — there is no analogous compact JSON, so the combination is a
   * mistake rather than a silent no-op.
   */
  static void requireCompactCompatibleWith(boolean asYaml, boolean compact)
  {
    if (compact && !asYaml)
      throw new IllegalArgumentException(
          "compact applies only to YAML output; drop it or use format: yaml");
  }
}
