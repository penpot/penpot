/**
 * Delimiter check for Clojure/EDN before a write lands. Reader-lite: do ( [ { balance?
 *
 * Skipped as data (delimiters inside never count):
 * - `;` comments, `#!` lines: to end of line.
 * - string and `#"regex"` bodies: backslash escapes next char (reader does same).
 * - char literals: backslash outside string skips next char. `\(` `\"` `\;` `\\`
 *   are data; `\newline` `\space` leave only letters.
 *
 * No case needed for the rest: `#{` `#(` `#?(` `#?@(` open with a plain
 * delimiter; `#_` discards a form that must balance anyway.
 *
 * Positions: line:col, 1-based, col in code points.
 */

const CLOSER: Record<string, string> = { "(": ")", "[": "]", "{": "}" };

interface Open {
    ch: string;
    at: string;
    /** Outermost entry only: first col-1 opener seen while open. */
    nestedTop?: string;
}

/** Null when balanced, else one clause (no final period) naming the first
 *  problem and its line:col. */
export function delimiterProblem(src: string): string | null {
    const n = src.length;
    const stack: Open[] = [];
    let i = 0;
    let line = 1;
    let col = 0; // col of the last code point read

    // Consume one code point; keep line:col current.
    const advance = (): string => {
        const c = src.charCodeAt(i);
        if (c === 10) {
            i++;
            line++;
            col = 0;
            return "\n";
        }
        col++;
        if (c >= 0xd800 && c <= 0xdbff && i + 1 < n) {
            const d = src.charCodeAt(i + 1);
            if (d >= 0xdc00 && d <= 0xdfff) {
                i += 2;
                return src.slice(i - 2, i);
            }
        }
        return src[i++];
    };

    const skipLine = (): void => {
        while (i < n && src.charCodeAt(i) !== 10) advance();
    };

    // After the opening quote. False when the file ends first.
    const skipString = (): boolean => {
        while (i < n) {
            const c = advance();
            if (c === '"') return true;
            if (c === "\\") {
                if (i >= n) return false;
                advance();
            }
        }
        return false;
    };

    while (i < n) {
        const ch = advance();
        switch (ch) {
            case ";":
                skipLine();
                break;
            case '"': {
                const at = `${line}:${col}`;
                if (!skipString()) return `unterminated string starting at ${at}`;
                break;
            }
            case "\\": {
                const at = `${line}:${col}`;
                if (i >= n) return `character literal at ${at} has no character before the end of the file`;
                advance();
                break;
            }
            case "#": {
                const at = `${line}:${col}`;
                const c = src[i];
                if (c === '"') {
                    advance();
                    if (!skipString()) return `unterminated regex starting at ${at}`;
                } else if (c === "!") {
                    skipLine();
                }
                break;
            }
            case "(":
            case "[":
            case "{": {
                const at = `${line}:${col}`;
                if (col === 1 && stack.length > 0) stack[0].nestedTop ??= `'${ch}' at ${at}`;
                stack.push({ ch, at });
                break;
            }
            case ")":
            case "]":
            case "}": {
                const at = `${line}:${col}`;
                const top = stack.pop();
                if (!top) return `unmatched '${ch}' at ${at} with nothing open`;
                if (CLOSER[top.ch] !== ch) {
                    return `mismatched '${ch}' at ${at} where the '${top.ch}' opened at ${top.at} needs '${CLOSER[top.ch]}'`;
                }
                break;
            }
        }
    }

    if (stack.length === 0) return null;
    const outer = stack[0];
    let problem = `unclosed '${outer.ch}' opened at ${outer.at}`;
    if (stack.length > 1) problem += ` and ${stack.length - 1} more inside it`;
    if (outer.nestedTop) {
        problem += `; the ${outer.nestedTop} starts a top-level form inside it, so the missing closer is probably just before that line`;
    }
    return problem;
}
