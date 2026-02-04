import { Board, Fill, FlexLayout, GridLayout, Page, Rectangle, Shape } from "@penpot/plugin-types";

export class PenpotUtils {
    /**
     * Generates an overview structure of the given shape,
     * providing its id, name and type, and recursively its children's attributes.
     * The `type` field indicates the type in the Penpot API.
     * If the shape has a layout system (flex or grid), includes layout information.
     *
     * @param shape - The root shape to generate the structure from
     * @param maxDepth - Optional maximum depth to traverse (leave undefined for unlimited)
     * @returns An object representing the shape structure
     */
    public static shapeStructure(shape: Shape, maxDepth: number | undefined = undefined): object {
        let children = undefined;
        if (maxDepth === undefined || maxDepth > 0) {
            if ("children" in shape && shape.children) {
                children = shape.children.map((child) =>
                    this.shapeStructure(child, maxDepth === undefined ? undefined : maxDepth - 1)
                );
            }
        }

        const result: any = {
            id: shape.id,
            name: shape.name,
            type: shape.type,
            children: children,
        };

        // add layout information if present
        if ("flex" in shape && shape.flex) {
            const flex: FlexLayout = shape.flex;
            result.layout = {
                type: "flex",
                dir: flex.dir,
                rowGap: flex.rowGap,
                columnGap: flex.columnGap,
            };
        } else if ("grid" in shape && shape.grid) {
            const grid: GridLayout = shape.grid;
            result.layout = {
                type: "grid",
                rows: grid.rows,
                columns: grid.columns,
                rowGap: grid.rowGap,
                columnGap: grid.columnGap,
            };
        }

        return result;
    }

    /**
     * Finds all shapes that matches the given predicate in the given shape tree.
     *
     * @param predicate - A function that takes a shape and returns true if it matches the criteria
     * @param root - The root shape to start the search from (defaults to penpot.root)
     */
    public static findShapes(predicate: (shape: Shape) => boolean, root: Shape | null = penpot.root): Shape[] {
        let result = new Array<Shape>();

        let find = function (shape: Shape | null) {
            if (!shape) {
                return;
            }
            if (predicate(shape)) {
                result.push(shape);
            }
            if ("children" in shape && shape.children) {
                for (let child of shape.children) {
                    find(child);
                }
            }
        };

        find(root);
        return result;
    }

    /**
     * Finds the first shape that matches the given predicate in the given shape tree.
     *
     * @param predicate - A function that takes a shape and returns true if it matches the criteria
     * @param root - The root shape to start the search from (if null, searches all pages)
     */
    public static findShape(predicate: (shape: Shape) => boolean, root: Shape | null = null): Shape | null {
        let find = function (shape: Shape | null): Shape | null {
            if (!shape) {
                return null;
            }
            if (predicate(shape)) {
                return shape;
            }
            if ("children" in shape && shape.children) {
                for (let child of shape.children) {
                    let result = find(child);
                    if (result) {
                        return result;
                    }
                }
            }
            return null;
        };

        if (root === null) {
            const pages = penpot.currentFile?.pages;
            if (pages) {
                for (let page of pages) {
                    let result = find(page.root);
                    if (result) {
                        return result;
                    }
                }
            }
            return null;
        } else {
            return find(root);
        }
    }

    /**
     * Finds a shape by its unique ID.
     *
     * @param id - The unique ID of the shape to find
     * @returns The shape with the matching ID, or null if not found
     */
    public static findShapeById(id: string): Shape | null {
        return this.findShape((shape) => shape.id === id);
    }

    public static findPage(predicate: (page: Page) => boolean): Page | null {
        let page = penpot.currentFile!.pages.find(predicate);
        return page || null;
    }

    public static getPages(): { id: string; name: string }[] {
        return penpot.currentFile!.pages.map((page) => ({ id: page.id, name: page.name }));
    }

    public static getPageById(id: string): Page | null {
        return this.findPage((page) => page.id === id);
    }

    public static getPageByName(name: string): Page | null {
        return this.findPage((page) => page.name.toLowerCase() === name.toLowerCase());
    }

    public static getPageForShape(shape: Shape): Page | null {
        for (const page of penpot.currentFile!.pages) {
            if (page.getShapeById(shape.id)) {
                return page;
            }
        }
        return null;
    }

    public static generateCss(shape: Shape): string {
        const page = this.getPageForShape(shape);
        if (!page) {
            throw new Error("Shape is not part of any page");
        }
        penpot.openPage(page);
        return penpot.generateStyle([shape], { type: "css", includeChildren: true });
    }

    /**
     * Checks if a child shape is fully contained within its parent's bounds.
     * Visual containment means all edges of the child are within the parent's bounding box.
     *
     * @param child - The child shape to check
     * @param parent - The parent shape to check against
     * @returns true if child is fully contained within parent bounds, false otherwise
     */
    public static isContainedIn(child: Shape, parent: Shape): boolean {
        return (
            child.x >= parent.x &&
            child.y >= parent.y &&
            child.x + child.width <= parent.x + parent.width &&
            child.y + child.height <= parent.y + parent.height
        );
    }

    /**
     * Sets the position of a shape relative to its parent's position.
     * This is a convenience method since parentX and parentY are read-only properties.
     *
     * @param shape - The shape to position
     * @param parentX - The desired X position relative to the parent
     * @param parentY - The desired Y position relative to the parent
     * @throws Error if the shape has no parent
     */
    public static setParentXY(shape: Shape, parentX: number, parentY: number): void {
        if (!shape.parent) {
            throw new Error("Shape has no parent - cannot set parent-relative position");
        }
        shape.x = shape.parent.x + parentX;
        shape.y = shape.parent.y + parentY;
    }

    /**
     * Adds a flex layout to a container while preserving the visual order of existing children.
     * Without this, adding a flex layout can arbitrarily reorder children.
     *
     * The method sorts children by their current position (x for "row", y for "column") before
     * adding the layout, then reorders them to maintain that visual sequence.
     *
     * @param container - The container (board) to add the flex layout to
     * @param dir - The layout direction: "row" for horizontal, "column" for vertical
     * @returns The created FlexLayout instance
     */
    public static addFlexLayout(container: Board, dir: "column" | "row"): FlexLayout {
        // obtain children sorted by position (ascending)
        const children = "children" in container && container.children ? [...container.children] : [];
        const sortedChildren = children.sort((a, b) => (dir === "row" ? a.x - b.x : a.y - b.y));

        // add the flex layout
        const flexLayout = container.addFlexLayout();
        flexLayout.dir = dir;

        // reorder children to preserve visual order; since the children array is reversed
        // relative to visual order for dir="column" or dir="row", we insert each child at
        // index 0 in sorted order, which places the first (smallest position) at the highest
        // index, making it appear first visually
        for (const child of sortedChildren) {
            child.setParentIndex(0);
        }

        return flexLayout;
    }

    /**
     * Analyzes all descendants of a shape by applying an evaluator function to each.
     * Only descendants for which the evaluator returns a non-null/non-undefined value are included in the result.
     * This is a general-purpose utility for validation, analysis, or collecting corrector functions.
     *
     * @param root - The root shape whose descendants to analyze
     * @param evaluator - Function called for each descendant with (root, descendant); return null/undefined to skip
     * @param maxDepth - Optional maximum depth to traverse (undefined for unlimited)
     * @returns Array of objects containing the shape and the evaluator's result
     */
    public static analyzeDescendants<T>(
        root: Shape,
        evaluator: (root: Shape, descendant: Shape) => T | null | undefined,
        maxDepth: number | undefined = undefined
    ): Array<{ shape: Shape; result: NonNullable<T> }> {
        const results: Array<{ shape: Shape; result: NonNullable<T> }> = [];

        const traverse = (shape: Shape, currentDepth: number): void => {
            const result = evaluator(root, shape);
            if (result !== null && result !== undefined) {
                results.push({ shape, result: result as NonNullable<T> });
            }

            if (maxDepth === undefined || currentDepth < maxDepth) {
                if ("children" in shape && shape.children) {
                    for (const child of shape.children) {
                        traverse(child, currentDepth + 1);
                    }
                }
            }
        };

        // Start traversal with root's children (not root itself)
        if ("children" in root && root.children) {
            for (const child of root.children) {
                traverse(child, 1);
            }
        }

        return results;
    }

    /**
     * Decodes a base64 string to a Uint8Array.
     * This is required because the Penpot plugin environment does not provide the atob function.
     *
     * @param base64 - The base64-encoded string to decode
     * @returns The decoded data as a Uint8Array
     */
    public static atob(base64: string): Uint8Array {
        const chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        const lookup = new Uint8Array(256);
        for (let i = 0; i < chars.length; i++) {
            lookup[chars.charCodeAt(i)] = i;
        }

        let bufferLength = base64.length * 0.75;
        if (base64[base64.length - 1] === "=") {
            bufferLength--;
            if (base64[base64.length - 2] === "=") {
                bufferLength--;
            }
        }

        const bytes = new Uint8Array(bufferLength);
        let p = 0;
        for (let i = 0; i < base64.length; i += 4) {
            const encoded1 = lookup[base64.charCodeAt(i)];
            const encoded2 = lookup[base64.charCodeAt(i + 1)];
            const encoded3 = lookup[base64.charCodeAt(i + 2)];
            const encoded4 = lookup[base64.charCodeAt(i + 3)];

            bytes[p++] = (encoded1 << 2) | (encoded2 >> 4);
            bytes[p++] = ((encoded2 & 15) << 4) | (encoded3 >> 2);
            bytes[p++] = ((encoded3 & 3) << 6) | (encoded4 & 63);
        }

        return bytes;
    }

    /**
     * Imports an image from base64 data into the Penpot design as a Rectangle shape filled with the image.
     * The rectangle has the image's original proportions by default.
     * Optionally accepts position (x, y) and dimensions (width, height) parameters.
     * If only one dimension is provided, the other is calculated to maintain the image's aspect ratio.
     *
     * This function is used internally by the ImportImageTool in the MCP server.
     *
     * @param base64 - The base64-encoded image data
     * @param mimeType - The MIME type of the image (e.g., "image/png")
     * @param name - The name to assign to the newly created rectangle shape
     * @param x - The x-coordinate for positioning the rectangle (optional)
     * @param y - The y-coordinate for positioning the rectangle (optional)
     * @param width - The desired width of the rectangle (optional)
     * @param height - The desired height of the rectangle (optional)
     */
    public static async importImage(
        base64: string,
        mimeType: string,
        name: string,
        x: number | undefined,
        y: number | undefined,
        width: number | undefined,
        height: number | undefined
    ): Promise<Rectangle> {
        // convert base64 to Uint8Array
        const bytes = PenpotUtils.atob(base64);

        // upload the image data to Penpot
        const imageData = await penpot.uploadMediaData(name, bytes, mimeType);

        // create a rectangle shape
        const rect = penpot.createRectangle();
        rect.name = name;

        // calculate dimensions
        let rectWidth, rectHeight;
        const hasWidth = width !== undefined;
        const hasHeight = height !== undefined;

        if (hasWidth && hasHeight) {
            // both width and height provided - use them directly
            rectWidth = width;
            rectHeight = height;
        } else if (hasWidth) {
            // only width provided - maintain aspect ratio
            rectWidth = width;
            rectHeight = rectWidth * (imageData.height / imageData.width);
        } else if (hasHeight) {
            // only height provided - maintain aspect ratio
            rectHeight = height;
            rectWidth = rectHeight * (imageData.width / imageData.height);
        } else {
            // neither provided - use original dimensions
            rectWidth = imageData.width;
            rectHeight = imageData.height;
        }

        // set rectangle dimensions
        rect.resize(rectWidth, rectHeight);

        // set position if provided
        if (x !== undefined) {
            rect.x = x;
        }
        if (y !== undefined) {
            rect.y = y;
        }

        // apply the image as a fill
        rect.fills = [{ fillOpacity: 1, fillImage: imageData }];

        return rect;
    }

    /**
     * Exports the given shape (or its fill) to BASE64 image data.
     *
     * This function is used internally by the ExportImageTool in the MCP server.
     *
     * @param shape - The shape whose image data to export
     * @param mode - Either "shape" (to export the entire shape, including descendants) or "fill"
     *    to export the shape's raw fill image data
     * @param asSVG - Whether to export as SVG rather than as a pixel image (only supported for mode "shape")
     * @returns A byte array containing the exported image data.
     *   - For mode="shape", it will be PNG or SVG data depending on the value of `asSVG`.
     *   - For mode="fill", it will be whatever format the fill image is stored in.
     */
    public static async exportImage(shape: Shape, mode: "shape" | "fill", asSVG: boolean): Promise<Uint8Array> {
        switch (mode) {
            case "shape":
                return shape.export({ type: asSVG ? "svg" : "png" });
            case "fill":
                if (asSVG) {
                    throw new Error("Image fills cannot be exported as SVG");
                }
                // check whether the shape has the `fills` member
                if (!("fills" in shape)) {
                    throw new Error("Shape with `fills` member is required for fill export mode");
                }
                // find first fill that has fillImage
                const fills: Fill[] = (shape as any).fills;
                for (const fill of fills) {
                    if (fill.fillImage) {
                        const imageData = fill.fillImage;
                        return imageData.data();
                    }
                }
                throw new Error("No fill with image data found in the shape");
            default:
                throw new Error(`Unsupported export mode: ${mode}`);
        }
    }
}
