/**
 * Bencode, the wire format of nREPL.
 *
 * Byte strings decode to UTF-8 strings: nREPL sends code, output, and values as UTF-8 text, and every other
 * field it sends is ASCII.
 */

export type BValue = number | string | BValue[] | { [key: string]: BValue };

/**
 * Encodes a value. Dictionary keys are written in sorted order, as the format requires.
 */
export function encode(value: BValue): Buffer {
    const parts: Buffer[] = [];
    const write = (v: BValue): void => {
        if (typeof v === "number") {
            if (!Number.isInteger(v)) throw new TypeError(`bencode integers only, got ${v}`);
            parts.push(Buffer.from(`i${v}e`));
        } else if (typeof v === "string") {
            const bytes = Buffer.from(v, "utf8");
            parts.push(Buffer.from(`${bytes.length}:`), bytes);
        } else if (Array.isArray(v)) {
            parts.push(Buffer.from("l"));
            v.forEach(write);
            parts.push(Buffer.from("e"));
        } else {
            parts.push(Buffer.from("d"));
            for (const key of Object.keys(v).sort()) {
                write(key);
                write(v[key]);
            }
            parts.push(Buffer.from("e"));
        }
    };
    write(value);
    return Buffer.concat(parts);
}

/** Thrown by {@link decode} when the bytes cannot be bencode. */
export class BencodeError extends Error {}

const INCOMPLETE = Symbol("incomplete");

/**
 * Decodes one value starting at `start`.
 *
 * @returns the value and the offset just past it, or `null` when the buffer ends inside the value (more bytes
 *     are needed)
 * @throws BencodeError on bytes that cannot start or continue a value
 */
export function decode(buf: Buffer, start = 0): { value: BValue; end: number } | null {
    let pos = start;

    const digitsUntil = (terminator: number): number | typeof INCOMPLETE => {
        const end = buf.indexOf(terminator, pos);
        if (end < 0) return INCOMPLETE;
        const text = buf.toString("ascii", pos, end);
        if (!/^-?\d+$/.test(text)) throw new BencodeError(`bad number '${text}' at offset ${pos}`);
        pos = end + 1;
        return Number(text);
    };

    const read = (): BValue | typeof INCOMPLETE => {
        if (pos >= buf.length) return INCOMPLETE;
        const head = buf[pos];
        if (head === 0x69 /* i */) {
            pos++;
            return digitsUntil(0x65 /* e */);
        }
        if (head === 0x6c /* l */ || head === 0x64 /* d */) {
            pos++;
            const items: BValue[] = [];
            for (;;) {
                if (pos >= buf.length) return INCOMPLETE;
                if (buf[pos] === 0x65 /* e */) {
                    pos++;
                    break;
                }
                const item = read();
                if (item === INCOMPLETE) return INCOMPLETE;
                items.push(item);
            }
            if (head === 0x6c) return items;
            const dict: { [key: string]: BValue } = {};
            for (let k = 0; k + 1 < items.length; k += 2) {
                dict[String(items[k])] = items[k + 1];
            }
            return dict;
        }
        if (head >= 0x30 && head <= 0x39) {
            const length = digitsUntil(0x3a /* : */);
            if (length === INCOMPLETE) return INCOMPLETE;
            if (pos + length > buf.length) return INCOMPLETE;
            const text = buf.toString("utf8", pos, pos + length);
            pos += length;
            return text;
        }
        throw new BencodeError(`unexpected byte 0x${head.toString(16)} at offset ${pos}`);
    };

    const value = read();
    return value === INCOMPLETE ? null : { value, end: pos };
}
