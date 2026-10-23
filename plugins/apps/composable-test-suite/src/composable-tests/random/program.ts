export type RandomKind = "main" | "copy" | "reset" | "duplicate" | "detach" | "resize" | "rotate" | "sync";
export type RandomStep = readonly [RandomKind, number, number, number];

/** A portable PRNG. Programs, rather than API handles, form the replay data. */
export function makeProgram(seed: number, steps: number): RandomStep[] {
    let state = seed >>> 0;
    const next = () => {
        state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
        return state;
    };
    const kinds: RandomKind[] = ["main", "copy", "reset", "duplicate", "detach", "resize", "rotate", "sync"];
    return Array.from({ length: steps }, () => {
        const kind = kinds[(next() >>> 16) % kinds.length];
        return [kind, next() >>> 16, next() >>> 16, next() >>> 16] as const;
    });
}
