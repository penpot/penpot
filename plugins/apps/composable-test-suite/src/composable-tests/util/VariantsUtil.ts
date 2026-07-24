import { Board, VariantContainer } from "@penpot/plugin-types";

/** One component to combine into a variant container: its board and property values. */
export interface VariantComponentSpec {
    /** The main-instance board on the canvas. */
    shape: Board;
    /** This component's variant property values, as `{ propertyName: value }`. */
    properties: Record<string, string>;
}

/**
 * Helpers for building variant containers through the Plugin API.
 */
export class VariantsUtil {
    /**
     * Creates a variant container from a list of main component instances and
     * configures its property names and values in one call. Encapsulates the
     * multi-step workflow: combine the components, name the properties, then set
     * each component's property values.
     *
     * @param components - the main-instance boards to combine, each with the
     *     variant property values that identify it
     * @returns the newly created variant container
     */
    static create(components: readonly VariantComponentSpec[]): VariantContainer {
        // collect all property names in first-seen order
        const propNames: string[] = [];
        for (const { properties } of components) {
            for (const name of Object.keys(properties)) {
                if (!propNames.includes(name)) propNames.push(name);
            }
        }

        // combine the components into a variant container
        // (createVariantFromComponents is not in the pinned plugin-types, but exists at runtime)
        const container = (
            penpot as unknown as {
                createVariantFromComponents(shapes: Board[]): VariantContainer;
            }
        ).createVariantFromComponents(components.map((c) => c.shape));

        const variants = container.variants;
        if (variants === null) {
            throw new Error(
                "createVariantContainer: the created container has no `variants`; " +
                    "ensure every provided shape is a main component instance."
            );
        }

        // name the properties (createVariantFromComponents starts with exactly one)
        for (let i = 0; i < propNames.length; i++) {
            if (i === 0) {
                variants.renameProperty(0, propNames[0]);
            } else {
                variants.addProperty();
                variants.renameProperty(i, propNames[i]);
            }
        }

        // Set each component's property values driven by the INPUT specs, NOT by
        // variants.variantComponents(): createVariantFromComponents reorders the
        // variant components unpredictably relative to the input shapes, so iterating
        // its output and pairing by position writes values onto the wrong members.
        // Each input shape's id is preserved, so its own component() is the matching
        // variant component — set its properties directly.
        for (const { shape, properties } of components) {
            const comp = shape.component();
            if (comp === null || !penpot.utils.types.isVariantComponent(comp)) {
                throw new Error(`source shape "${shape.name}" is not a variant component after combining`);
            }
            for (let propIdx = 0; propIdx < propNames.length; propIdx++) {
                const value = properties[propNames[propIdx]];
                if (value !== undefined) {
                    comp.setVariantProperty(propIdx, value);
                }
            }
        }

        return container;
    }
}
