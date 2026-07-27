import { Board, Shape, VariantContainer } from "@penpot/plugin-types";
import { Situation } from "../core/Situation";
import { Operation } from "../core/Operation";
import { ShapeUtil } from "../util/ShapeUtil.ts";
import { ContentCreationStrategy } from "../content-creation/ContentCreationStrategy";
import {
    ContentCreationStrategyInstantiateVariantContainer,
    VARIANT_INSTANCE_NAME,
} from "../content-creation/ContentCreationStrategyInstantiateVariantContainer";
import { VariantsUtil } from "../util/VariantsUtil.ts";

/** The single variant property this op defines; its values are 0-based indices. */
const VARIANT_PROPERTY = "variantIdx";

/**
 * The foundation operation that creates a variant container. Given one content-
 * creation strategy per variant, it builds a component from each (its content
 * supplied by that strategy) and combines them into a variant container, under a
 * single property whose value is the variant's 0-based index.
 *
 * The created container is stored in the situation, keyed by this op; later steps
 * reach it through this op's `getVariantContainer`. The op is the interface to its
 * own data — callers ask the op, not the situation's raw store.
 *
 * For instantiating a variant of the created container, the op vends a content
 * strategy via `contentStrategyForVariant`, wired to resolve this container at
 * apply-time — so a later instantiate step needs nothing but the chosen index.
 */
export class OpCreateVariantContainer extends Operation {
    private readonly variantStrategies: readonly ContentCreationStrategy[];

    /**
     * @param variantStrategies - one content-creation strategy per variant, in
     *     index order; each defines the content of that variant's component
     */
    constructor(...variantStrategies: ContentCreationStrategy[]) {
        super();
        this.variantStrategies = variantStrategies;
    }

    async applyTo(situation: Situation): Promise<void> {
        // build one component per variant, its content supplied by the strategy
        const mains = this.variantStrategies.map((strategy, index) => {
            const board = penpot.createBoard();
            board.name = `Variant ${index}`;
            board.resize(100, 100);
            strategy.createContent(situation, board);
            const component = penpot.library.local.createComponent([board]);
            return component.mainInstance() as Board;
        });

        // combine them into a variant container, one property keyed by index
        const container = VariantsUtil.create(
            mains.map((shape, index) => ({ shape, properties: { [VARIANT_PROPERTY]: String(index) } }))
        );

        situation.setData(this, container);
        situation.applyPosAdvanceX(container);
    }

    toString(): string {
        return "create variant container";
    }

    /**
     * The variant container this op created, read from `situation`. Throws if this
     * op has not yet run in the trajectory.
     */
    getVariantContainer(situation: Situation): VariantContainer {
        const container = situation.getData(this) as VariantContainer | undefined;
        if (container === undefined) {
            throw new Error("variant container not created yet; run OpCreateVariantContainer first");
        }
        return container;
    }

    /**
     * The variant-component instance this op's strategy placed, located within
     * `root` by name. Use it to turn a structural nested head (from the nestable
     * op) into the actual variant instance to switch:
     * `variantContainer.getVariantInstance(nestable.getNestedInstanceHead(s, level))`.
     * Returns `root` itself if it is the variant instance. Throws if none is found.
     */
    getVariantInstance(root: Shape): Shape {
        const instance =
            root.name === VARIANT_INSTANCE_NAME
                ? root
                : ShapeUtil.findShape(root, (shape) => shape.name === VARIANT_INSTANCE_NAME);
        if (instance === null) {
            throw new Error(`no variant instance "${VARIANT_INSTANCE_NAME}" found within "${root.name}"`);
        }
        return instance;
    }

    /**
     * The variant property's position (for `switchVariant`). A single property, so
     * always position 0.
     */
    get variantPropertyPosition(): number {
        return 0;
    }

    /** The property value identifying variant `index` (its 0-based index as a string). */
    valueForVariant(index: number): string {
        return String(index);
    }

    /**
     * Returns a content creation strategy that instantiates variant `index` of this op's
     * container.
     */
    createContentCreationStrategy(index: number): ContentCreationStrategyInstantiateVariantContainer {
        return new ContentCreationStrategyInstantiateVariantContainer((s) => this.getVariantContainer(s), index);
    }
}
