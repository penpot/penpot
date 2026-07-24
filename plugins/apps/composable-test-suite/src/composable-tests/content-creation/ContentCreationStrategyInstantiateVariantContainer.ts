import { Board, Shape, VariantContainer } from "@penpot/plugin-types";
import { Situation } from "../core/Situation.ts";
import { ShapeTarget, resolveTarget } from "../core/ShapeTarget.ts";
import { ContentCreationStrategy } from "./ContentCreationStrategy.ts";

/**
 * The name given to the variant-component instance this strategy creates, so it
 * can be found again as the switch target — including as its image inside each
 * nested copy (a component instance copies its descendants' names, so the name
 * propagates through nesting).
 */
export const VARIANT_INSTANCE_NAME = "MyComponent";

/**
 * A content-creation strategy that instantiates one variant of an existing variant
 * container into the given board. The container is supplied as a `ShapeTarget`
 * resolved from the situation at apply-time, so this strategy can be constructed
 * before the container exists (e.g. wired to the op that will create it) and used
 * once it does. `createContent` instantiates the chosen variant's component,
 * names it (so it can be located later as the switch head), and appends it to the
 * board.
 */
export class ContentCreationStrategyInstantiateVariantContainer extends ContentCreationStrategy {
    /**
     * @param container - resolves the variant container from the situation
     * @param variantIndex - the 0-based index of the variant to instantiate
     */
    constructor(
        private readonly container: ShapeTarget,
        private readonly variantIndex: number
    ) {
        super();
    }

    createContent(situation: Situation, board: Board): void {
        const container = resolveTarget(this.container, situation) as VariantContainer;
        const variants = container.variants;
        if (variants === null) {
            throw new Error("the resolved shape is not a variant container");
        }

        const components = variants.variantComponents();
        const component = components[this.variantIndex];
        if (component === undefined) {
            throw new Error(`no variant at index ${this.variantIndex} (have ${components.length})`);
        }

        const instance = component.instance() as Shape;
        instance.name = VARIANT_INSTANCE_NAME;
        board.appendChild(instance);
    }
}
