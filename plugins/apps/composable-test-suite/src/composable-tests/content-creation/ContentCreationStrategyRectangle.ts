import { Board, Shape } from "@penpot/plugin-types";
import { Situation } from "../core/Situation.ts";
import { Color } from "../model/Color.ts";
import { ShapeUtil } from "../util/ShapeUtil.ts";
import { ContentCreationStrategy } from "./ContentCreationStrategy.ts";

/**
 * A content-creation strategy whose component content is a single named rectangle.
 * It locates that rectangle inside any instance of the component by name, so the
 * "the rect of this instance" accessor works at any nesting depth (the rect is
 * found by descending whatever instance is handed in).
 */
export class ContentCreationStrategyRectangle extends ContentCreationStrategy {
    /** The name given to the single rectangle this strategy places in a component. */
    static readonly RECT_SHAPE_NAME = "Child";

    /**
     * @param baselineColor - the rectangle's initial fill colour
     */
    constructor(private readonly baselineColor: Color) {
        super();
    }

    createContent(_situation: Situation, board: Board): void {
        const rect = penpot.createRectangle();
        rect.name = ContentCreationStrategyRectangle.RECT_SHAPE_NAME;
        rect.resize(50, 50);
        rect.fills = [{ fillColor: this.baselineColor.hex, fillOpacity: this.baselineColor.opacity }];
        board.appendChild(rect);
    }

    /**
     * Returns this strategy's rectangle as it appears inside the given root shape.
     * Throws if it cannot be found.
     *
     * @param instance - the root shape/component instance to search within
     */
    static findRectangle(instance: Shape): Shape {
        const rect = ShapeUtil.findShape(
            instance,
            (shape) => shape.name === ContentCreationStrategyRectangle.RECT_SHAPE_NAME
        );
        if (rect === null) {
            throw new Error(
                `Could not find child "${ContentCreationStrategyRectangle.RECT_SHAPE_NAME}" inside instance "${instance.name}"`
            );
        }
        return rect;
    }

    /**
     * Returns this strategy's rectangle as it appears inside the given root shape.
     * Throws if it cannot be found.
     *
     * @param instance - the root shape/component instance to search within
     */
    getRectangle(instance: Shape): Shape {
        return ContentCreationStrategyRectangle.findRectangle(instance);
    }
}
