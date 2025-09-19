# Variants

## General info
Variants are components that are grouped, and share a bunch of properties. On this document, they will be refered simply as `components`. The group itself will be refered as `VariantComponent`.

The minimun number of components on a VariantComponent is one.


## Components
* They have a property `variant-id`, with the same value for all of them, that marks that they belog to a VariantComponent.
* The have an attribute `variant-properties`, that is a map like `[{name: 'Property 1', value: 'Value 1'} {name: 'Property 2', value: 'Value 2'}]`
    * All of the components on the same VariantComponent must have the same number of properties, with the same keys, on the same order
    * The values can be the same or different


## Main shapes of the components
### Variant container
All the main shapes of the components of the same VariantComponent must be direct children of a variant container. This variant container is a frame with the following characteristics:
* Its `id` is the `variant-id` of the components that belong to this VariantComponent
* It has a property `is-variant-container` with value `true`.
* It is invalid to have a variant container without children
* The first child of the variant-container will be used to represent visually the entire VariantComponent in different places on Penpot, so the order of the children is important.
* When it is created on the interface of Penpot, it has some attributes (it is flex, it have a stroke with the color #bb97d8, it have a border radius on 20...). Those attributes are not mandatory. TBD: Will the SDK create those attributes by itself?

### Main shape
The main shape of a component that belongs to a VariantComponent has some extra attributes:
* `variant-id` The same value as its component's `variant-id`, that also is the same value of the `id` of the variant container
* `variant-name` A string created by getting the values of the `variant-properties` of its component, removing the empty ones, and joined by `", "`. So `[{name: 'Property 1', value: 'Value 1'} {name: 'Property 2', value: ''} {name: 'Property 3', value: 'Value 3'}]` will become `Value 1, Value 3`

### Copies of the components
The copies of the components that belong to a VariantComponent do not have any differences with other copies. They don't have special attributes.