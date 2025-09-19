# Variants

## General

Variants are components that are grouped and share a set of properties. In this document they are referred simply as **components**. The group itself is called a **VariantComponent**.

A VariantComponent must contain at least **one** component.

## Components

- Each component has a `variant-id` attribute. **All components in the same VariantComponent share the same `variant-id`.**
- Each component has a `variant-properties` attribute, which consists on a list/map of properties. For example: `[{ name: "Property 1", value: "Value 1" } { name: "Property 2", value: "Value 2" }]`.
  * All components within a VariantComponent must have the same number of properties, with identical keys and in the same order.
  * The values can be the same or different across components.

## Main shapes of components

### Variant container

All main shapes for components of the same VariantComponent must be direct children of a **variant container**. A variant container is a frame with the following characteristics:
- Its `id` equals the shared `variant-id` of its child components.
- It has an attribute `is-variant-container` set to `true`.
- A variant container without children is invalid. It must have at least one child.
- The first child of the variant container is used to represent visually the entire VariantComponent in different places on Penpot, so the order of the children is significant.
- When created in the interface of Penpot, it may include some attributes (e.g., flex layout, stroke color `#bb97d8`, border radius `20`). These attributes are not mandatory. (TBD: Will the SDK create those attributes by itself?).

### Main shape

The main shape of a component that belongs to a VariantComponent includes some extra attributes:
* `variant-id`: same value as its component's `variant-id` and the variant container's `id`.
* `variant-name`: a string composed by taking the values of the `variant-properties` of its component, removing the empty ones, and joining the remaining with `", "`. Example: `[{ name: 'Property 1', value: 'Value 1' } { name: 'Property 2', value: '' } { name: 'Property 3', value: 'Value 3' }]` becomes `Value 1, Value 3`.

### Copies of the components

Copies of components within a VariantComponent do not have any special attributes and behave like ordinary copies.
