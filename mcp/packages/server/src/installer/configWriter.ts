import fs from "node:fs";
import path from "node:path";

export function expandHome(p: string): string {
    if (p.startsWith("~")) {
        const home = process.env.HOME || process.env.USERPROFILE || "";
        return path.join(home, p.slice(1));
    }
    return p;
}

export function readJsonFile<T = any>(filePath: string): T | undefined {
    if (!fs.existsSync(filePath)) {
        return undefined;
    }
    const raw = fs.readFileSync(filePath, "utf8");
    if (!raw.trim()) {
        return undefined;
    }
    return JSON.parse(stripJsoncComments(raw)) as T;
}

export function writeJsonFile(filePath: string, data: unknown): void {
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
    fs.writeFileSync(filePath, JSON.stringify(data, null, 2) + "\n", "utf8");
}

export function backupIfExists(filePath: string): string | undefined {
    if (!fs.existsSync(filePath)) {
        return undefined;
    }
    const backup = `${filePath}.bak-${Date.now()}`;
    fs.copyFileSync(filePath, backup);
    return backup;
}

export function stripJsoncComments(raw: string): string {
    let out = "";
    let i = 0;
    let inString = false;
    let stringChar = "";
    while (i < raw.length) {
        const c = raw[i];
        const next = raw[i + 1];
        if (inString) {
            out += c;
            if (c === "\\" && next !== undefined) {
                out += next;
                i += 2;
                continue;
            }
            if (c === stringChar) {
                inString = false;
            }
            i++;
            continue;
        }
        if (c === '"' || c === "'") {
            inString = true;
            stringChar = c;
            out += c;
            i++;
            continue;
        }
        if (c === "/" && next === "/") {
            while (i < raw.length && raw[i] !== "\n") {
                i++;
            }
            continue;
        }
        if (c === "/" && next === "*") {
            i += 2;
            while (i < raw.length && !(raw[i] === "*" && raw[i + 1] === "/")) {
                i++;
            }
            i += 2;
            continue;
        }
        out += c;
        i++;
    }
    return out;
}

export function setNested(obj: any, keys: string[], value: unknown): void {
    let cursor = obj;
    for (let i = 0; i < keys.length - 1; i++) {
        const k = keys[i];
        if (typeof cursor[k] !== "object" || cursor[k] === null) {
            cursor[k] = {};
        }
        cursor = cursor[k];
    }
    cursor[keys[keys.length - 1]] = value;
}

export function deleteNested(obj: any, keys: string[]): boolean {
    let cursor = obj;
    for (let i = 0; i < keys.length - 1; i++) {
        const k = keys[i];
        if (typeof cursor[k] !== "object" || cursor[k] === null) {
            return false;
        }
        cursor = cursor[k];
    }
    const last = keys[keys.length - 1];
    if (last in cursor) {
        delete cursor[last];
        return true;
    }
    return false;
}
