import { execFile } from "node:child_process";
import { writeFile, readFile, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { throwValidation, throwProcessing } from "./errors.js";
import { createLogger } from "../logger.js";
import type { FileInput } from "../types.js";

const logger = createLogger("font");

let fontProcessMem = 512;
let fontProcessCpuTime = 30;
let fontTimeout = 120000;

export function configureFontLimits(opts: { mem: number; cpuTime: number; timeout: number }): void {
  fontProcessMem = opts.mem;
  fontProcessCpuTime = opts.cpuTime;
  fontTimeout = opts.timeout;
}

function execCommand(
  cmd: string,
  args: string[],
  timeout?: number,
  options?: { encoding?: BufferEncoding | "buffer" }
): Promise<{ stdout: string | Buffer; stderr: string | Buffer }> {
  const effectiveTimeout = timeout ?? fontTimeout;
  const encoding = options?.encoding ?? "utf8";

  // Use prlimit on Linux for memory + CPU resource limits
  // Matches backend's prlimit-based font processing protection
  const isLinux = process.platform === "linux";
  let finalCmd = cmd;
  let finalArgs = args;

  if (isLinux && cmd !== "prlimit") {
    // Wrap with prlimit: address space ceiling + CPU time limit
    const prlimitArgs = [
      `--as=${fontProcessMem * 1024 * 1024}`, // address space (memory)
      `--cpu=${fontProcessCpuTime}`, // CPU seconds
      "--",
      cmd,
      ...args,
    ];
    finalCmd = "prlimit";
    finalArgs = prlimitArgs;
  }

  return new Promise((resolve, reject) => {
    execFile(
      finalCmd,
      finalArgs,
      { timeout: effectiveTimeout, encoding: encoding === "buffer" ? null : encoding },
      (err, stdout, stderr) => {
        if (err) {
          reject(new Error(`Command failed: ${finalCmd} ${finalArgs.join(" ")}\n${stderr}`));
        } else {
          resolve({ stdout, stderr });
        }
      }
    );
  });
}

async function withTempDir<T>(fn: (dir: string) => Promise<T>): Promise<T> {
  const dir = await mkdtemp(join(tmpdir(), "penpot.font."));
  try {
    return await fn(dir);
  } finally {
    // Cleanup is best-effort; temp dir auto-cleaned on reboot
    try {
      await rm(dir, { recursive: true, force: true }).catch(() => {});
    } catch {
      // ignore
    }
  }
}

async function fontConvert(inputExt: string, outputExt: string, input: FileInput): Promise<Buffer | null> {
  return withTempDir(async (dir) => {
    let inputPath: string;
    if (typeof input === "string") {
      inputPath = input; // Use path directly — avoids reading file into heap
    } else {
      inputPath = join(dir, `input${inputExt}`);
      await writeFile(inputPath, input); // Write buffer to temp file
    }

    const outputPath = join(dir, `input${outputExt}`);
    try {
      // Escape single quotes for FontForge's string parser (not shell).
      // execFile passes args as an array — no shell injection vector.
      // FontForge's own lexer uses doubled single quotes for escaping.
      const escInput = inputPath.replace(/'/g, "''");
      const escOutput = outputPath.replace(/'/g, "''");
      await execCommand("fontforge", ["-lang=ff", "-c", `Open('${escInput}'); Generate('${escOutput}')`]);
      return await readFile(outputPath);
    } catch (err: unknown) {
      const error = err as NodeJS.ErrnoException & { killed?: boolean; signal?: string };
      // Detect resource limit kills from prlimit (SIGKILL = OOM, SIGXCPU = CPU time exceeded)
      if (error.killed || error.signal === "SIGKILL" || error.signal === "SIGXCPU") {
        logger.warn({ err, inputExt, outputExt }, "FontForge killed by resource limits");
        throwProcessing("resource-limit-exceeded", "Font processing exceeded resource limits");
      }
      logger.warn({ err, inputExt, outputExt }, "FontForge conversion failed");
      return null;
    }
  });
}

async function ttfToOtf(input: FileInput): Promise<Buffer | null> {
  return fontConvert(".ttf", ".otf", input);
}

async function otfToTtf(input: FileInput): Promise<Buffer | null> {
  return fontConvert(".otf", ".ttf", input);
}

async function sfntToWoff(input: FileInput): Promise<Buffer | null> {
  return withTempDir(async (dir) => {
    let inputPath: string;
    if (typeof input === "string") {
      inputPath = input; // Use path directly
    } else {
      inputPath = join(dir, "input.ttf");
      await writeFile(inputPath, input); // Write buffer to temp file
    }

    try {
      await execCommand("sfnt2woff", [inputPath]);
      const output = join(dir, "input.woff");
      return await readFile(output);
    } catch (err) {
      logger.warn({ err }, "sfnt2woff conversion failed");
      return null;
    }
  });
}

async function woffToSfnt(input: FileInput): Promise<Buffer | null> {
  return withTempDir(async (dir) => {
    let inputPath: string;
    if (typeof input === "string") {
      inputPath = input; // Use path directly
    } else {
      inputPath = join(dir, "input.woff");
      await writeFile(inputPath, input); // Write buffer to temp file
    }

    try {
      const { stdout } = await execCommand("woff2sfnt", [inputPath], undefined, { encoding: "buffer" });
      return stdout as Buffer;
    } catch (err) {
      logger.warn({ err }, "woff2sfnt conversion failed");
      return null;
    }
  });
}

async function woff2ToSfnt(input: FileInput): Promise<Buffer | null> {
  return withTempDir(async (dir) => {
    let inputPath: string;
    if (typeof input === "string") {
      inputPath = input; // Use path directly
    } else {
      inputPath = join(dir, "input.woff2");
      await writeFile(inputPath, input); // Write buffer to temp file
    }

    const output = join(dir, "input.ttf");
    try {
      await execCommand("woff2_decompress", [inputPath]);
      return await readFile(output);
    } catch (err) {
      logger.warn({ err }, "woff2_decompress failed");
      return null;
    }
  });
}

function getSfntType(data: Buffer): "ttf" | "otf" {
  const magic = data.subarray(0, 4).toString("hex");
  switch (magic) {
    case "4f54544f":
      return "otf";
    case "00010000":
      return "ttf";
    default:
      throwValidation("invalid-font", "Unrecognized font format");
  }
}

async function convertFromSfnt(sfnt: Buffer, targetType: string): Promise<Buffer | null> {
  if (targetType === "ttf") {
    const stype = getSfntType(sfnt);
    if (stype === "ttf") return sfnt;
    return otfToTtf(sfnt);
  }
  if (targetType === "otf") {
    const stype = getSfntType(sfnt);
    if (stype === "otf") return sfnt;
    return ttfToOtf(sfnt);
  }
  if (targetType === "woff") {
    return sfntToWoff(sfnt);
  }
  return null;
}

export async function convertFont(input: FileInput, sourceMtype: string, targetMtype: string): Promise<Buffer | null> {
  const sourceType = sourceMtype.replace("font/", "");
  const targetType = targetMtype.replace("font/", "");

  // Same type: return data as-is
  if (sourceType === targetType) {
    if (typeof input === "string") {
      return readFile(input); // Read file into Buffer for same-type return
    }
    return input;
  }

  // Source is TTF
  if (sourceType === "ttf") {
    if (targetType === "otf") return ttfToOtf(input);
    if (targetType === "woff") return sfntToWoff(input);
    return null;
  }

  // Source is OTF
  if (sourceType === "otf") {
    if (targetType === "ttf") return otfToTtf(input);
    if (targetType === "woff") return sfntToWoff(input);
    return null;
  }

  // Source is WOFF: extract sfnt first, then convert
  if (sourceType === "woff") {
    const sfnt = await woffToSfnt(input);
    if (!sfnt) {
      throwValidation("invalid-font", "Could not extract SFNT from WOFF");
    }
    if (targetType === "woff") {
      if (typeof input === "string") {
        return readFile(input); // Read file into Buffer for same-type return
      }
      return input; // preserve original
    }
    return convertFromSfnt(sfnt, targetType);
  }

  // Source is WOFF2: decompress to sfnt, then convert
  const sfnt = await woff2ToSfnt(input);
  if (!sfnt) {
    throwValidation("invalid-font", "Could not decompress WOFF2");
  }
  return convertFromSfnt(sfnt, targetType);
}
