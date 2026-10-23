import type { Board, Shape } from "@penpot/plugin-types";
import { Operation } from "../core/Operation.ts";
import { Situation } from "../core/Situation.ts";
import { makeProgram, type RandomStep } from "../random/program.ts";
import { PenpotSync } from "../util/PenpotSync.ts";

type Field = "fills" | "opacity";
type Overrides = Map<string, Partial<Record<Field, unknown>>>;

function visible(field: Field, value: unknown): unknown {
    if (field !== "fills") return value;
    const fills = value as Shape["fills"];
    return Array.isArray(fills) ? fills.map((fill) => [fill.fillColor?.toLowerCase(), fill.fillOpacity ?? 1]) : fills;
}

function children(shape: Shape): Shape[] {
    return "children" in shape ? (shape as Board).children : [];
}

function subtree(shape: Shape): Shape[] {
    return [shape, ...children(shape).flatMap(subtree)];
}

/** Random edits through the real Plugin API, with an independent value model. */
export class OpRandomSequence extends Operation {
    constructor(
        readonly seed: number,
        readonly program: readonly RandomStep[] = makeProgram(seed, 32)
    ) {
        super();
    }

    async applyTo(situation: Situation): Promise<void> {
        const overrides: Overrides = new Map();
        const roots: Shape[] = [];
        const leafBoard = penpot.createBoard();
        leafBoard.resize(100, 100);
        leafBoard.name = `Random leaf ${this.seed}`;
        const rect = penpot.createRectangle();
        rect.resize(40, 30);
        rect.name = "Random child";
        rect.fills = [{ fillColor: "#ABCDEF", fillOpacity: 1 }];
        leafBoard.appendChild(rect);
        rect.x = leafBoard.x + 10;
        rect.y = leafBoard.y + 10;
        const leaf = penpot.library.local.createComponent([leafBoard]);
        const outerBoard = penpot.createBoard();
        outerBoard.resize(200, 200);
        outerBoard.name = `Random outer ${this.seed}`;
        outerBoard.appendChild(leaf.instance());
        const outer = penpot.library.local.createComponent([outerBoard]);
        roots.push(leaf.mainInstance(), outer.mainInstance(), leaf.instance(), outer.instance(), outer.instance());
        roots.forEach((root) => situation.applyPosAdvanceX(root));
        await PenpotSync.awaitPropagation();

        const forget = (root: Shape) => subtree(root).forEach((shape) => overrides.delete(shape.id));
        const check = () => {
            for (const shape of roots.flatMap(subtree)) {
                const main = shape.componentRefShape();
                if (!main) continue;
                for (const field of ["fills", "opacity"] as const) {
                    const own = overrides.get(shape.id);
                    const expected = own && field in own ? own[field] : visible(field, main[field]);
                    const actual = visible(field, shape[field]);
                    if (JSON.stringify(actual) !== JSON.stringify(expected)) {
                        throw new Error(
                            `Visible override rule failed for ${shape.name}.${field}: ` +
                                `expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`
                        );
                    }
                }
            }
        };
        const prefix: RandomStep[] = [
            ["copy", 0, 0, 1],
            ["main", 0, 1, 1],
            ["reset", 0, 0, 0],
        ];
        for (const [index, tuple] of [...prefix, ...this.program].entries()) {
            const [kind, selector, property, value] = tuple;
            const all = roots.flatMap(subtree);
            const copyHeads = all.filter((shape) => shape.isComponentHead() && shape.isComponentCopyInstance());
            let candidates: Shape[];
            switch (kind) {
                case "main":
                    candidates = all.filter((shape) => shape.isComponentMainInstance() && !shape.componentRefShape());
                    break;
                case "copy":
                    candidates = all.filter((shape) => shape.componentRefShape());
                    break;
                case "detach":
                    candidates = copyHeads.filter(
                        (shape) =>
                            !shape.parent?.isComponentCopyInstance() &&
                            subtree(shape)
                                .slice(1)
                                .every((child) => !child.isComponentHead())
                    );
                    break;
                case "duplicate":
                    candidates = roots.filter((shape) => shape.isComponentCopyInstance() && roots.length < 12);
                    break;
                case "reset":
                    candidates = copyHeads;
                    break;
                case "rotate":
                case "resize":
                    candidates = roots.filter((shape) => shape.isComponentCopyInstance());
                    break;
                case "sync":
                    candidates = [];
                    break;
            }
            const shape = candidates.length ? candidates[selector % candidates.length] : undefined;
            situation.recordApplication(
                `seed ${this.seed}, step ${index}: ${JSON.stringify(tuple)} ` +
                    (shape ? `target ${all.indexOf(shape)}` : kind === "sync" ? "settle" : "skip: no eligible target")
            );
            if (!shape && kind !== "sync") continue;
            switch (kind) {
                case "main":
                case "copy": {
                    const field = property % 2 === 0 ? "fills" : "opacity";
                    const next =
                        field === "fills"
                            ? [{ fillColor: ["#ABCDEF", "#FF0000", "#00FF00"][value % 3], fillOpacity: 1 }]
                            : [1, 0.4, 0.7][value % 3];
                    if (JSON.stringify(visible(field, shape![field])) !== JSON.stringify(visible(field, next))) {
                        if (shape!.componentRefShape()) {
                            overrides.set(shape!.id, { ...overrides.get(shape!.id), [field]: visible(field, next) });
                        }
                        if (field === "fills") shape!.fills = next as Shape["fills"];
                        else shape!.opacity = next as number;
                    }
                    break;
                }
                case "reset":
                    forget(shape!);
                    (shape as Shape & { resetOverrides(): void }).resetOverrides();
                    break;
                case "detach":
                    forget(shape!);
                    shape!.detach();
                    break;
                case "duplicate": {
                    const copy = shape!.clone();
                    const source = subtree(shape!);
                    subtree(copy).forEach((child, i) => {
                        const own = overrides.get(source[i].id);
                        if (own) overrides.set(child.id, { ...own });
                    });
                    roots.push(copy);
                    situation.applyPosAdvanceX(copy);
                    break;
                }
                case "resize":
                    shape!.resize(shape!.width, 80 + (value % 6) * 20);
                    break;
                case "rotate":
                    shape!.rotation += 45;
                    break;
                case "sync":
                    break;
            }
            await PenpotSync.awaitPropagation();
            try {
                check();
            } catch (error) {
                throw new Error(`${String(error)}\nReplay: seed ${this.seed}, program ${JSON.stringify(this.program)}`);
            }
        }
        check();
    }

    toString(): string {
        return `random component program, seed ${this.seed}`;
    }
}
