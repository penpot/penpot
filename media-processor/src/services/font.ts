import { execFile } from "node:child_process";
import { writeFile, readFile, mkdtemp, rm, copyFile } from "node:fs/promises";
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

export function execCommand(
  cmd: string,
  args: string[],
  timeout?: number,
  options?: { encoding?: BufferEncoding | "buffer"; signal?: AbortSignal }
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
      {
        timeout: effectiveTimeout,
        encoding: encoding === "buffer" ? null : encoding,
        signal: options?.signal,
      },
      (err, stdout, stderr) => {
        if (err) {
          const error = new Error(`Command failed: ${finalCmd} ${finalArgs.join(" ")}\n${stderr}`);
          if (err.killed) (error as any).killed = err.killed;
          if (err.signal) (error as any).signal = err.signal;
          if (err.code !== null && err.code !== undefined) (error as any).code = err.code;
          reject(error);
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
    await rm(dir, { recursive: true, force: true }).catch(() => {});
  }
}

async function withTempInput<T>(
  ext: string,
  input: FileInput,
  fn: (dir: string, inputPath: string) => Promise<T>
): Promise<T> {
  return withTempDir(async (dir) => {
    const inputPath = join(dir, `input${ext}`);
    if (typeof input === "string") {
      await copyFile(input, inputPath);
    } else {
      await writeFile(inputPath, input);
    }
    return fn(dir, inputPath);
  });
}

async function fontConvert(
  inputExt: string,
  outputExt: string,
  input: FileInput,
  signal?: AbortSignal
): Promise<Buffer | null> {
  return withTempDir(async (dir) => {
    let inputPath: string;
    if (typeof input === "string") {
      inputPath = input; // Use path directly — avoids reading file into heap
    } else {
      inputPath = join(dir, `input${inputExt}`);
      await writeFile(inputPath, input); // Write buffer to temp file
    }

    // Ensure input path is from tmpdir to prevent injection
    if (!inputPath.startsWith(tmpdir())) {
      throw new Error("Font processing denied: input path is outside expected directory");
    }

    const outputPath = join(dir, `input${outputExt}`);
    try {
      // Escape single quotes for FontForge's string parser (not shell).
      // execFile passes args as an array — no shell injection vector.
      // FontForge's own lexer uses doubled single quotes for escaping.
      const escInput = inputPath.replace(/'/g, "''");
      const escOutput = outputPath.replace(/'/g, "''");
      await execCommand("fontforge", ["-lang=ff", "-c", `Open('${escInput}'); Generate('${escOutput}')`], undefined, {
        signal,
      });
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

async function ttfToOtf(input: FileInput, signal?: AbortSignal): Promise<Buffer | null> {
  return fontConvert(".ttf", ".otf", input, signal);
}

async function otfToTtf(input: FileInput, signal?: AbortSignal): Promise<Buffer | null> {
  return fontConvert(".otf", ".ttf", input, signal);
}

async function sfntToWoff(input: FileInput, ext: string = ".ttf", signal?: AbortSignal): Promise<Buffer | null> {
  return withTempInput(ext, input, async (dir, inputPath) => {
    try {
      await execCommand("sfnt2woff", [inputPath], undefined, { signal });
      const output = join(dir, "input.woff");
      return await readFile(output);
    } catch (err: unknown) {
      const error = err as NodeJS.ErrnoException & { killed?: boolean; signal?: string };
      if (error.killed || error.signal === "SIGKILL" || error.signal === "SIGXCPU") {
        logger.warn({ err }, "sfnt2woff killed by resource limits");
        throwProcessing("resource-limit-exceeded", "Font processing exceeded resource limits");
      }
      logger.warn({ err }, "sfnt2woff conversion failed");
      return null;
    }
  });
}

async function woffToSfnt(input: FileInput, signal?: AbortSignal): Promise<Buffer | null> {
  return withTempInput(".woff", input, async (_dir, inputPath) => {
    try {
      const { stdout } = await execCommand("woff2sfnt", [inputPath], undefined, { encoding: "buffer", signal });
      return stdout as Buffer;
    } catch (err: unknown) {
      const error = err as NodeJS.ErrnoException & { killed?: boolean; signal?: string };
      if (error.killed || error.signal === "SIGKILL" || error.signal === "SIGXCPU") {
        logger.warn({ err }, "woff2sfnt killed by resource limits");
        throwProcessing("resource-limit-exceeded", "Font processing exceeded resource limits");
      }
      logger.warn({ err }, "woff2sfnt conversion failed");
      return null;
    }
  });
}

async function woff2ToSfnt(input: FileInput, signal?: AbortSignal): Promise<Buffer | null> {
  return withTempInput(".woff2", input, async (dir, inputPath) => {
    const output = join(dir, "input.ttf");
    try {
      await execCommand("woff2_decompress", [inputPath], undefined, { signal });
      return await readFile(output);
    } catch (err: unknown) {
      const error = err as NodeJS.ErrnoException & { killed?: boolean; signal?: string };
      if (error.killed || error.signal === "SIGKILL" || error.signal === "SIGXCPU") {
        logger.warn({ err }, "woff2_decompress killed by resource limits");
        throwProcessing("resource-limit-exceeded", "Font processing exceeded resource limits");
      }
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

async function convertFromSfnt(sfnt: Buffer, targetType: string, signal?: AbortSignal): Promise<Buffer | null> {
  if (targetType === "ttf") {
    const stype = getSfntType(sfnt);
    if (stype === "ttf") return sfnt;
    return otfToTtf(sfnt, signal);
  }
  if (targetType === "otf") {
    const stype = getSfntType(sfnt);
    if (stype === "otf") return sfnt;
    return ttfToOtf(sfnt, signal);
  }
  if (targetType === "woff") {
    return sfntToWoff(sfnt, ".ttf", signal);
  }
  return null;
}

function validateFontSignature(data: Buffer, expectedType: string): void {
  if (data.length < 4) {
    throwValidation("invalid-font", "Font data too short");
  }

  const magic = data.subarray(0, 4).toString("hex");

  switch (expectedType) {
    case "ttf":
      if (magic !== "00010000") {
        throwValidation("invalid-font", "Invalid TTF signature");
      }
      break;
    case "otf":
      if (magic !== "4f54544f") {
        throwValidation("invalid-font", "Invalid OTF signature");
      }
      break;
    case "woff":
      if (magic !== "774f4646") {
        throwValidation("invalid-font", "Invalid WOFF signature");
      }
      break;
    case "woff2":
      if (magic !== "774f4632") {
        throwValidation("invalid-font", "Invalid WOFF2 signature");
      }
      break;
  }
}

export async function convertFont(
  input: FileInput,
  sourceMtype: string,
  targetMtype: string,
  signal?: AbortSignal
): Promise<Buffer | null> {
  const sourceType = sourceMtype.replace("font/", "");
  const targetType = targetMtype.replace("font/", "");

  // Same type: validate signature and return data as-is
  if (sourceType === targetType) {
    let data: Buffer;
    if (typeof input === "string") {
      data = await readFile(input);
    } else {
      data = input;
    }
    validateFontSignature(data, sourceType);
    return data;
  }

  // Source is TTF
  if (sourceType === "ttf") {
    if (targetType === "otf") return ttfToOtf(input, signal);
    if (targetType === "woff") return sfntToWoff(input, ".ttf", signal);
    return null;
  }

  // Source is OTF
  if (sourceType === "otf") {
    if (targetType === "ttf") return otfToTtf(input, signal);
    if (targetType === "woff") return sfntToWoff(input, ".otf", signal);
    return null;
  }

  // Source is WOFF: extract sfnt first, then convert
  if (sourceType === "woff") {
    const sfnt = await woffToSfnt(input, signal);
    if (!sfnt) {
      throwValidation("invalid-font", "Could not extract SFNT from WOFF");
    }
    return convertFromSfnt(sfnt, targetType, signal);
  }

  // Source is WOFF2: decompress to sfnt, then convert
  const sfnt = await woff2ToSfnt(input, signal);
  if (!sfnt) {
    throwValidation("invalid-font", "Could not decompress WOFF2");
  }
  return convertFromSfnt(sfnt, targetType, signal);
}
