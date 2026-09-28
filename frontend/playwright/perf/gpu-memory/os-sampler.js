// OS-level memory of the browser under test. Linux only: reads /proc, so it
// works the same for Chromium, Firefox and WebKit. The process that owns the
// GL context is found by who holds a GPU device open, not by engine names.
import { execFile } from "node:child_process";
import { readdir, readFile, readlink } from "node:fs/promises";
import { basename } from "node:path";
import { promisify } from "node:util";

const run = promisify(execFile);

const GPU_DEVICE =
  /^\/dev\/(dri\/(renderD|card)\d+|nvidia(\d+|ctl|-uvm|-modeset))$/;
const DMABUF = /^(\/dmabuf:|anon_inode:dmabuf)/;
const DRM_KEY =
  /^drm-(total|resident|shared|active|purgeable|memory)-([\w-]+):\s+(\d+)(?:\s*(KiB|MiB|GiB))?/;
const UNIT = { KiB: 1024, MiB: 1024 ** 2, GiB: 1024 ** 3 };

// Only processes whose binary path contains this are sampled.
const PROC_MATCH = process.env.PERF_PROC_MATCH ?? "ms-playwright";

async function safe(promise, fallback = null) {
  try {
    return await promise;
  } catch (_) {
    return fallback;
  }
}

async function browserPids() {
  const pids = [];
  for (const entry of await readdir("/proc")) {
    if (!/^\d+$/.test(entry)) continue;
    const exe = await safe(readlink(`/proc/${entry}/exe`));
    if (exe?.includes(PROC_MATCH)) pids.push({ pid: Number(entry), exe });
  }
  return pids;
}

function roleOf(exe, cmdline) {
  const typeArg = cmdline.find((a) => a.startsWith("--type="));
  if (typeArg) return typeArg.slice("--type=".length);
  if (cmdline.includes("-contentproc")) return `content:${cmdline.at(-1)}`;
  return basename(exe);
}

async function rollup(pid) {
  const text = await safe(readFile(`/proc/${pid}/smaps_rollup`, "utf8"), "");
  const kib = (key) => {
    const m = text.match(new RegExp(`^${key}:\\s+(\\d+) kB`, "m"));
    return m ? Number(m[1]) * 1024 : 0;
  };
  return { rss: kib("Rss"), pss: kib("Pss") };
}

async function fdScan(pid) {
  const fdDir = `/proc/${pid}/fd`;
  const fds = await safe(readdir(fdDir), []);
  const devices = new Set();
  const drm = {};
  const drmClients = new Set();
  const dmabufs = new Map();

  for (const fd of fds) {
    const link = await safe(readlink(`${fdDir}/${fd}`));
    if (!link) continue;
    const isGpu = GPU_DEVICE.test(link);
    const isDmabuf = DMABUF.test(link);
    if (!isGpu && !isDmabuf) continue;
    if (isGpu) devices.add(link);

    const info = await safe(readFile(`/proc/${pid}/fdinfo/${fd}`, "utf8"), "");
    if (isDmabuf) {
      const ino = info.match(/^ino:\s+(\d+)/m)?.[1] ?? `${pid}:${fd}`;
      const size = Number(info.match(/^size:\s+(\d+)/m)?.[1] ?? 0);
      dmabufs.set(ino, size);
      continue;
    }

    // One DRM client can sit behind several fds; count it once.
    const client = info.match(/^drm-client-id:\s+(\d+)/m)?.[1];
    if (!client || drmClients.has(client)) continue;
    drmClients.add(client);
    for (const line of info.split("\n")) {
      const m = line.match(DRM_KEY);
      if (!m) continue;
      const key = `${m[1]}-${m[2]}`;
      drm[key] = (drm[key] ?? 0) + Number(m[3]) * (UNIT[m[4]] ?? 1);
    }
  }

  return { devices: [...devices], drm, dmabufs };
}

let nvidiaAvailable = true;

async function nvidia(pids) {
  if (!nvidiaAvailable) return null;
  const xml = await safe(run("nvidia-smi", ["-q", "-x"], { timeout: 3000 }));
  if (!xml) {
    nvidiaAvailable = false;
    return null;
  }
  const out = xml.stdout;
  const used = out.match(/<fb_memory_usage>[\s\S]*?<used>(\d+) MiB<\/used>/);
  const perProcess = {};
  for (const block of out.matchAll(
    /<process_info>([\s\S]*?)<\/process_info>/g,
  )) {
    const pid = Number(block[1].match(/<pid>(\d+)<\/pid>/)?.[1]);
    const mem = block[1].match(/<used_memory>(\d+) MiB<\/used_memory>/)?.[1];
    if (pids.has(pid) && mem) perProcess[pid] = Number(mem) * UNIT.MiB;
  }
  return {
    usedBytes: used ? Number(used[1]) * UNIT.MiB : null,
    perProcess,
  };
}

export async function sampleOs() {
  if (process.platform !== "linux") return { unsupported: process.platform };

  const procs = [];
  for (const { pid, exe } of await browserPids()) {
    let cmdline = (await safe(readFile(`/proc/${pid}/cmdline`, "utf8"), ""))
      .split("\0")
      .filter(Boolean);
    // Chromium rewrites its argv into one space-joined string.
    if (cmdline.length === 1) cmdline = cmdline[0].split(" ");
    const [mem, fds] = await Promise.all([rollup(pid), fdScan(pid)]);
    procs.push({ pid, role: roleOf(exe, cmdline), ...mem, ...fds });
  }

  const gpuProcs = procs.filter((p) => p.devices.length > 0);
  const sum = (list, f) => list.reduce((acc, p) => acc + f(p), 0);
  // The same dma-buf is often open in several processes; count it once.
  const dmabufs = new Map();
  for (const p of procs)
    for (const [ino, size] of p.dmabufs) dmabufs.set(ino, size);
  const drm = {};
  for (const p of gpuProcs) {
    for (const [k, v] of Object.entries(p.drm)) drm[k] = (drm[k] ?? 0) + v;
  }

  return {
    browserRss: sum(procs, (p) => p.rss),
    browserPss: sum(procs, (p) => p.pss),
    gpuProcessRss: sum(gpuProcs, (p) => p.rss),
    gpuProcessPss: sum(gpuProcs, (p) => p.pss),
    gpuProcesses: gpuProcs.map((p) => ({
      pid: p.pid,
      role: p.role,
      rss: p.rss,
      devices: p.devices,
    })),
    drm,
    dmabuf: {
      count: dmabufs.size,
      bytes: [...dmabufs.values()].reduce((a, b) => a + b, 0),
    },
    nvidia: await nvidia(new Set(gpuProcs.map((p) => p.pid))),
  };
}
