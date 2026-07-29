import { z } from "zod";
import { Tool } from "../Tool";
import "reflect-metadata";
import type { ToolResponse } from "../ToolResponse";
import { TextResponse } from "../ToolResponse";
import type { PenpotMcpServer } from "../PenpotMcpServer";
import * as fs from "fs";

/**
 * Arguments for the FindUnclosedParensTool.
 */
export class CljCheckParenthesesArgs {
    static schema = {
        file: z.string().min(1).describe("Absolute path to a Clojure/ClojureScript source file"),
    };

    file!: string;
}

interface OpenDelim {
    id: number;
    line: number; // 0-based
    col: number; // 0-based
    char: string;
    baselineKey: string; // the baseline key this delimiter owns
}

interface ParenIssue {
    line: number; // 1-based
    col: number; // 1-based
    char: string;
    detectedAtLine?: number; // 1-based line where the stack-state mismatch was observed
}

/**
 * Finds unclosed delimiters in Clojure/ClojureScript source files using a
 * stack-state invariant derived from cljfmt formatting conventions.
 *
 * Invariant: in cljfmt-formatted code, every opening delimiter of type T at
 * column C must see the same stack depth each time that (T, C) combination
 * occurs. A depth mismatch means delimiters opened between the baseline
 * occurrence and the current one were never closed.
 *
 * The parser correctly handles string literals (including multi-line and escape
 * sequences), comment lines, character literals, and regex literals.
 */
export class CljCheckParentheses extends Tool<CljCheckParenthesesArgs> {
    constructor(mcpServer: PenpotMcpServer) {
        super(mcpServer, CljCheckParenthesesArgs.schema);
    }

    public getToolName(): string {
        return "clj_check_parentheses";
    }

    public getToolDescription(): string {
        return "Analyzes a Clojure/ClojureScript source file for unclosed delimiters and reports the area of interest.";
    }

    protected async executeCore(args: CljCheckParenthesesArgs): Promise<ToolResponse> {
        const filePath = args.file;

        if (!fs.existsSync(filePath)) {
            return new TextResponse(`File not found: ${filePath}`);
        }

        const content = fs.readFileSync(filePath, "utf-8");
        const issues = analyzeParens(content);

        if (issues.length === 0) {
            return new TextResponse("All delimiters are properly balanced.");
        }

        const sourceLines = content.split("\n");
        const parts: string[] = [`Found ${issues.length} unclosed delimiter(s):\n`];

        for (const issue of issues) {
            const srcLine = (sourceLines[issue.line - 1] ?? "").trimEnd();
            const pointer = " ".repeat(String(issue.line).length) + "   " + " ".repeat(issue.col - 1) + "^";

            if (issue.detectedAtLine != null) {
                const detectedSrcLine = (sourceLines[issue.detectedAtLine - 1] ?? "").trimEnd();
                parts.push(
                    `  Unclosed '${issue.char}' at line ${issue.line}, col ${issue.col}:\n` +
                        `    ${issue.line} | ${srcLine}\n` +
                        `    ${pointer}\n` +
                        `  Stack-state mismatch detected at line ${issue.detectedAtLine}:\n` +
                        `    ${issue.detectedAtLine} | ${detectedSrcLine}\n`
                );
            } else {
                parts.push(
                    `  Unclosed '${issue.char}' at line ${issue.line}, col ${issue.col} (still open at end of file):\n` +
                        `    ${issue.line} | ${srcLine}\n` +
                        `    ${pointer}\n`
                );
            }
        }

        return new TextResponse(parts.join("\n"));
    }
}

/**
 * Analyses delimiter balance in a Clojure/ClojureScript source string.
 *
 * Algorithm
 * ---------
 * Maintain a stack of open delimiters and a map from (delimiter-type, column)
 * to the stack depth recorded on the first occurrence of that combination.
 *
 * Each time an opening delimiter of type T appears at column C:
 *   1. Look up the key (T, C) in the map.
 *   2. If absent, record the current stack depth as the baseline.
 *   3. If present, compare the current depth with the baseline.
 *      - If deeper: the extra stack entries (from baseline depth to current
 *        depth) are delimiters that should have been closed. Report them.
 *      - If shallower: more delimiters were closed than opened between the
 *        baseline and here (over-closed). Update the baseline downward so
 *        subsequent occurrences don't cascade.
 *   4. Push the delimiter onto the stack.
 *
 * After the full file is processed, any delimiter still on the stack is
 * unclosed. If it was already reported via a mismatch, the report includes
 * the detection line; otherwise it is reported as open-at-EOF.
 */
function analyzeParens(content: string): ParenIssue[] {
    // Precompute line-start offsets for O(1) column lookup.
    const lineStarts: number[] = [0];
    for (let i = 0; i < content.length; i++) {
        if (content[i] === "\n") lineStarts.push(i + 1);
    }

    let nextId = 0;
    const stack: OpenDelim[] = [];

    // (type, column) → baseline stack depth.
    // Each baseline is owned by the delimiter that established it (stored
    // as baselineKey on the stack entry).  When that delimiter is popped,
    // its baseline is discarded — it was scoped to that delimiter's lifetime.
    const baseline: Map<string, number> = new Map();

    let inString = false;
    let inComment = false;
    let escape = false;
    let currentLine = 0;

    for (let i = 0; i < content.length; i++) {
        const ch = content[i];

        // ── Newline ──────────────────────────────────────────────────────
        if (ch === "\n") {
            inComment = false;
            currentLine++;
            if (!inString) escape = false;
            continue;
        }

        // ── Escape: skip next character ──────────────────────────────────
        if (escape) {
            escape = false;
            continue;
        }

        // ── Inside comment: skip until newline ───────────────────────────
        if (inComment) continue;

        // ── Inside string literal ────────────────────────────────────────
        if (inString) {
            if (ch === "\\") escape = true;
            else if (ch === '"') inString = false;
            continue;
        }

        // ── Outside string / comment ─────────────────────────────────────
        if (ch === "\\") {
            escape = true;
            continue;
        }
        if (ch === '"') {
            inString = true;
            continue;
        }
        if (ch === ";") {
            inComment = true;
            continue;
        }

        // ── Opening delimiter ────────────────────────────────────────────
        if (ch === "(" || ch === "[" || ch === "{") {
            const col = i - lineStarts[currentLine];
            const key = `${ch}:${col}`;
            const currentDepth = stack.length;

            const recorded = baseline.get(key);
            if (recorded !== undefined && currentDepth > recorded) {
                // Stack is deeper than expected.  The entries from index
                // `recorded` to `currentDepth - 1` are unclosed delimiters
                // that should have been closed before reaching this
                // position.  Return immediately — further parsing would be
                // against a corrupted stack and only produce cascading noise.
                return stack.slice(recorded, currentDepth).map((delim) => ({
                    line: delim.line + 1,
                    col: delim.col + 1,
                    char: delim.char,
                    detectedAtLine: currentLine + 1,
                }));
            }

            // Establish or re-establish the baseline for this key,
            // owned by this delimiter.  Discarded when it is popped.
            baseline.set(key, currentDepth);

            stack.push({
                id: nextId++,
                line: currentLine,
                col,
                char: ch,
                baselineKey: key,
            });
        }

        // ── Closing delimiter ────────────────────────────────────────────
        else if (ch === ")" || ch === "]" || ch === "}") {
            if (stack.length > 0) {
                const closed = stack.pop()!;

                // The baseline this delimiter owned is no longer valid —
                // the context it was recorded in has closed.
                baseline.delete(closed.baselineKey);
            }
        }
    }

    // ── EOF: no mismatch was found, but the stack is not empty ──────────
    // This happens when the unclosed delimiter has no second occurrence of
    // the same (type, column) to compare against (e.g. last form in file).
    return stack.map((delim) => ({
        line: delim.line + 1,
        col: delim.col + 1,
        char: delim.char,
    }));
}
