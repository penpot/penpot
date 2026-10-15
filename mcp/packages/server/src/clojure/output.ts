/**
 * Test JVM output shaped for the model: colour codes out; a test run's dots
 * counted, verdict and seed first, first failures kept; eval output capped
 * with a marker that says what to do instead. Every cut says how much it cut.
 */

const ANSI = /\x1b\[[0-9;?]*[ -/]*[@-~]/g;

/** Strip colour codes; trim trailing blank lines. */
export function cleanOutput(text: string): string {
    const lines = text.replace(ANSI, "").replace(/\r\n/g, "\n").split("\n");
    while (lines.length > 0 && lines[lines.length - 1].trim() === "") lines.pop();
    return lines.join("\n");
}

export interface Tail {
    text: string;
    cutLines: number;
    /** Chars cut from the head of the first kept line (one line alone over maxChars). */
    cutChars: number;
}

/** Last `maxLines` lines, at most `maxChars` chars of them. */
export function tail(text: string, maxLines: number, maxChars = Number.POSITIVE_INFINITY): Tail {
    if (text === "") return { text, cutLines: 0, cutChars: 0 };
    const lines = text.split("\n");
    let start = Math.max(0, lines.length - maxLines);
    let size = 0;
    for (let k = lines.length - 1; k >= start; k--) {
        size += lines[k].length + 1;
        if (size > maxChars) {
            start = k + 1;
            break;
        }
    }
    let cutChars = 0;
    let kept = lines.slice(start);
    // One line alone over budget (huge printed value): keep its end.
    if (kept.length === 0) {
        const last = lines[lines.length - 1];
        cutChars = last.length - maxChars;
        kept = [last.slice(cutChars)];
        start = lines.length - 1;
    }
    return { text: kept.join("\n"), cutLines: start, cutChars };
}

/** Tail, preceded by one line stating what was cut, if anything. */
export function renderTail(t: Tail): string {
    const notes: string[] = [];
    if (t.cutLines > 0) notes.push(`${t.cutLines} earlier lines cut`);
    if (t.cutChars > 0) notes.push(`the first ${t.cutChars} chars of the next line cut`);
    return notes.length > 0 ? `[${notes.join("; ")}]\n${t.text}` : t.text;
}

/** Failure blocks kept in a test result, in chars. */
export const BLOCK_CHARS = 16_000;
/** One line (an ex-data dump) in a test result. */
export const LINE_CHARS = 2_000;
/** Lines outside the failure blocks: run line, test output, totals. */
export const OTHER_CHARS = 4_000;
/** Eval output in all. */
export const EVAL_CHARS = 16_000;
/** Of EVAL_CHARS, kept from the start; the rest from the end, where values print. */
const EVAL_HEAD_CHARS = 4_000;
/** Names listed for the failure blocks cut. */
const CUT_NAMES = 40;

/** kaocha's dots reporter: `.` pass, F fail, E error, P pending, brackets group. */
const DOTS_LINE = /^[[\]().FEP]+$/;
/** A dots run sharing a line with test output. */
const DOTS_RUN = /[[\]().FEP]{20,}/g;
const DOT_EVENT = /[.FEP]/;
const BLOCK_START = /^(?:FAIL|ERROR) in /;
const VERDICT = /^\[testjvm\] \d+ tests, /;
const KAOCHA_SEED = /^Randomized with --seed \d+/;
const KAOCHA_TOTALS = /^\d+ tests, \d+ assertions, /;

function capLine(line: string): string {
    return line.length > LINE_CHARS ? `${line.slice(0, LINE_CHARS)} [${line.length - LINE_CHARS} chars cut]` : line;
}

function trimBlank(lines: string[]): string[] {
    let a = 0;
    let b = lines.length;
    while (a < b && lines[a].trim() === "") a++;
    while (b > a && lines[b - 1].trim() === "") b--;
    return lines.slice(a, b);
}

/** A test run for the model: verdict and seed first, the dots as one count,
 *  other lines tail-capped, then the first failure blocks up to BLOCK_CHARS
 *  and the names of the blocks cut. */
export function shapeRun(text: string): string {
    const counts = { pass: 0, fail: 0, error: 0, pending: 0 };
    const count = (run: string) => {
        for (const ch of run) {
            if (ch === ".") counts.pass++;
            else if (ch === "F") counts.fail++;
            else if (ch === "E") counts.error++;
            else if (ch === "P") counts.pending++;
        }
    };
    const verdicts: string[] = [];
    const seeds: string[] = [];
    const others: string[] = [];
    const blocks: string[][] = [];
    let block: string[] | null = null;
    for (const raw of text === "" ? [] : text.split("\n")) {
        if (DOTS_LINE.test(raw) && DOT_EVENT.test(raw)) {
            count(raw);
            continue;
        }
        const line = raw.replace(DOTS_RUN, (run) => {
            if (!DOT_EVENT.test(run)) return run;
            count(run);
            return "";
        });
        if (VERDICT.test(line)) {
            verdicts.push(line);
            block = null;
        } else if (KAOCHA_SEED.test(line)) {
            seeds.push(line);
            block = null;
        } else if (BLOCK_START.test(line)) {
            block = [line];
            blocks.push(block);
        } else if (KAOCHA_TOTALS.test(line)) {
            others.push(line);
            block = null;
        } else if (block) {
            block.push(line);
        } else if (line.trim() !== "") {
            others.push(line);
        }
    }

    const out = [...verdicts, ...seeds];
    const events = counts.pass + counts.fail + counts.error + counts.pending;
    if (events > 0) {
        const parts = [`${counts.pass} pass`];
        if (counts.fail > 0) parts.push(`${counts.fail} fail`);
        if (counts.error > 0) parts.push(`${counts.error} error`);
        if (counts.pending > 0) parts.push(`${counts.pending} pending`);
        out.push(`[kaocha dots: ${parts.join(", ")}]`);
    }
    const rest = others.map(capLine).join("\n");
    if (rest !== "") out.push(renderTail(tail(rest, Number.POSITIVE_INFINITY, OTHER_CHARS)));

    let used = 0;
    let kept = 0;
    for (const b of blocks) {
        let body = trimBlank(b).map(capLine).join("\n");
        if (kept > 0 && used + body.length > BLOCK_CHARS) break;
        if (body.length > BLOCK_CHARS) {
            body = `${body.slice(0, BLOCK_CHARS)}\n[${body.length - BLOCK_CHARS} more chars of this block cut]`;
        }
        out.push("", body);
        used += body.length;
        kept++;
    }
    const cut = blocks.slice(kept);
    if (cut.length > 0) {
        const names = cut.slice(0, CUT_NAMES).map((b) => b[0].trim());
        const more = cut.length > CUT_NAMES ? `; and ${cut.length - CUT_NAMES} more` : "";
        out.push("", `[${cut.length} more failure blocks cut: ${names.join("; ")}${more}]`);
    }
    return out.join("\n");
}

/** Eval output within EVAL_CHARS: the start and the end, where values print,
 *  with a marker naming the cut and what to do instead. */
export function capEval(text: string): string {
    if (text.length <= EVAL_CHARS) return text;
    const cut = text.length - EVAL_CHARS;
    return [
        text.slice(0, EVAL_HEAD_CHARS),
        `[${cut} of ${text.length} chars cut here. Bind the value instead of printing it whole, (def r <expr>), then print a slice: (subs (pr-str r) 0 2000), (take 10 r), or (keys r).]`,
        text.slice(text.length - (EVAL_CHARS - EVAL_HEAD_CHARS)),
    ].join("\n");
}
