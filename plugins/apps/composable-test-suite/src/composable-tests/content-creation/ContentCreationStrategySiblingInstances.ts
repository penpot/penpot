import { Board } from "@penpot/plugin-types";
import { Situation } from "../core/Situation";
import { Color } from "../model/Color";
import { ContentCreationStrategy } from "./ContentCreationStrategy";

/** The layout applied to the board holding the sibling instances. */
export type SiblingLayout = "none" | "flex" | "grid";

/**
 * A content-creation strategy that fills the board with several SIBLING instances
 * of one inner component: it creates a small inner component (a board with a
 * rectangle) and appends `count` instances of it side by side — breadth, in
 * contrast to the depth-wise wrapping of the nesting operations.
 *
 * Used for the swap-slot integrity cases: each sibling instance is a nested
 * sub-instance head whose `shape-ref` must positionally match the main's children
 * (see {@link ../util/SlotIntegrity}).
 *
 * The `layout` controls how the board arranges the siblings, which determines what
 * a later structural edit re-flows ("grid" re-runs `reorder-grid-children` on
 * changes, "flex" only repositions, "none" does nothing). Note that copy-side
 * deletes have proven safe under every layout (see the case D notes); the layout
 * is offered to keep the sweep space available, not because it is known to break.
 */
export class ContentCreationStrategySiblingInstances extends ContentCreationStrategy {
    /**
     * @param count - how many sibling instances of the inner component to append
     * @param baselineColor - the inner rectangle's fill colour
     * @param layout - the board layout arranging the siblings (default "none")
     */
    constructor(
        private readonly count: number,
        private readonly baselineColor: Color,
        private readonly layout: SiblingLayout = "none"
    ) {
        super();
    }

    createContent(_situation: Situation, board: Board): void {
        // inner component: a small board with a single rectangle
        const innerBoard = penpot.createBoard();
        innerBoard.name = "Icon";
        innerBoard.resize(24, 24);
        const rect = penpot.createRectangle();
        rect.name = "rect";
        rect.resize(24, 24);
        rect.fills = [{ fillColor: this.baselineColor.hex, fillOpacity: this.baselineColor.opacity }];
        innerBoard.appendChild(rect);
        const innerComponent = penpot.library.local.createComponent([innerBoard]);

        if (this.layout === "grid") {
            // a 1-row, N-column grid with each instance in its own cell
            const grid = board.addGridLayout();
            while (grid.rows.length < 1) grid.addRow("flex", 1);
            while (grid.columns.length < this.count) grid.addColumn("flex", 1);
            for (let i = 0; i < this.count; i++) {
                grid.appendChild(innerComponent.instance(), 1, i + 1);
            }
        } else {
            // add the flex layout BEFORE the children so they are laid out in order
            if (this.layout === "flex") board.addFlexLayout();
            for (let i = 0; i < this.count; i++) {
                board.appendChild(innerComponent.instance());
            }
        }
    }
}
