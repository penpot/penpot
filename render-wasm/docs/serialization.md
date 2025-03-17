# Serialization

## Shape Type

Shape types are serialized as `u8`:

| Value | Field  |
| ----- | ------ |
| 0     | Frame  |
| 1     | Group  |
| 2     | Bool   |
| 3     | Rect   |
| 4     | Path   |
| 5     | Text   |
| 6     | Circle |
| 7     | SvgRaw |
| 8     | Image  |
| \_    | Rect   |

## Horizontal Constraint

Horizontal constraints are serialized as `u8`:

| Value | Field     |
| ----- | --------- |
| 0     | Left      |
| 1     | Right     |
| 2     | LeftRight |
| 3     | Center    |
| 4     | Scale     |
| \_    | None      |

## Vertical Constraint

Vertical constraints are serialized as `u8`:

| Value | Field     |
| ----- | --------- |
| 0     | Top       |
| 1     | Bottom    |
| 2     | TopBottom |
| 3     | Center    |
| 4     | Scale     |
| \_    | None      |

## Paths

Paths are made of segments of **28 bytes** each. The layout (assuming positions in a `Uint8Array`) is the following:

| Offset | Length (bytes) | Data Type | Field   |
| ------ | -------------- | --------- | ------- |
| 0      | 2              | `u16`     | Command |
| 2      | 2              | `u16`     | Flags   |
| 4      | 4              | `f32`     | `c1_x`  |
| 8      | 4              | `f32`     | `c1_y`  |
| 12     | 4              | `f32`     | `c2_x`  |
| 16     | 4              | `f32`     | `c2_y`  |
| 20     | 4              | `f32`     | `x`     |
| 24     | 4              | `f32`     | `y`     |

**Command** can be one of these values:

- `:move-to`: `1`
- `:line-to`: `2`
- `:curve-to`: `3`
- `:close-path`: `4`

**Flags** is not being used at the moment.

## Gradient stops

Gradient stops are serialized in a `Uint8Array`, each stop taking **5 bytes**.

| Offset | Length (bytes) | Data Type | Field       |
| ------ | -------------- | --------- | ----------- |
| 0      | 1              | `u8`      | Red         |
| 1      | 1              | `u8`      | Green       |
| 2      | 1              | `u8`      | Blue        |
| 3      | 1              | `u8`      | Alpha       |
| 4      | 1              | `u8`      | Stop Offset |

**Red**, **Green**, **Blue** and **Alpha** are the RGBA components of the stop.

**Stop offset** is the offset, being integer values ranging from `0` to `100` (both inclusive).

## Stroke Caps

Stroke caps are serialized as `u8`:

| Value | Field     |
| ----- | --------- |
| 1     | Line      |
| 2     | Triangle  |
| 3     | Rectangle |
| 4     | Circle    |
| 5     | Diamond   |
| 6     | Round     |
| 7     | Square    |
| \_    | None      |

## Stroke Sytles

Stroke styles are serialized as `u8`:

| Value | Field  |
| ----- | ------ |
| 1     | Dotted |
| 2     | Dashed |
| 3     | Mixed  |
| \_    | Solid  |

## Bool Operations

Bool operations (`bool-type`) are serialized as `u8`:

| Value | Field        |
| ----- | ------------ |
| 0     | Union        |
| 1     | Difference   |
| 2     | Intersection |
| 3     | Exclusion    |
| \_    | Union        |

## BlurType

Blur types are serialized as `u8`:

| Value | Field |
| ----- | ----- |
| 1     | Layer |
| \_    | None  |

## Shadow Styles

Shadow styles are serialized as `u8`:

| Value | Field        |
| ----- | ------------ |
| 0     | Drop Shadow  |
| 1     | Inner Shadow |
| \_    | Drop Shadow  |

## Layout

### Direction

| Value | Field         |
| ----- | ------------- |
| 0     | Row           |
| 1     | RowReverse    |
| 2     | Column        |
| 3     | ColumnReverse |
| \_    | error         |

### Align Items

| Value | Field   |
| ----- | ------- |
| 0     | Start   |
| 1     | End     |
| 2     | Center  |
| 3     | Stretch |
| \_    | error   |

### Align self

| Value | Field   |
| ----- | ------- |
| 0     | Start   |
| 1     | End     |
| 2     | Center  |
| 3     | Stretch |
| \_    | error   |

### Align Content

| Value | Field         |
| ----- | ------------- |
| 0     | Start         |
| 1     | End           |
| 2     | Center        |
| 3     | Space between |
| 4     | Space around  |
| 5     | Space evenly  |
| 6     | Stretch       |
| \_    | error         |

### Justify items

| Value | Field   |
| ----- | ------- |
| 0     | Start   |
| 1     | End     |
| 2     | Center  |
| 3     | Stretch |
| \_    | error   |

### Justify content

| Value | Field         |
| ----- | ------------- |
| 0     | Start         |
| 1     | End           |
| 2     | Center        |
| 3     | Space between |
| 4     | Space around  |
| 5     | Space evenly  |
| 6     | Stretch       |
| \_    | error         |

### Wrap type

| Value | Field   |
| ----- | ------- |
| 0     | Wrap    |
| 1     | No Wrap |
| \_    | error   |

### Sizing

| Value | Field |
| ----- | ----- |
| 0     | Fill  |
| 1     | Fix   |
| 2     | Auto  |
| \_    | error |

## Font

### Style

| Value | Variant |
| ----- | ------- |
| 0     | Normal  |
| 1     | Italic  |
| \_    | Normal  |
