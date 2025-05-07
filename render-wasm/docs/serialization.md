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

## Fills

All fills take `160` bytes, but depending on the fill type, not all bytes are actually used.

### Solid color fills

| Offset | Length (bytes) | Data Type | Field      |
| ------ | -------------- | --------- | ---------- |
| 0      | 1              | `0x00`    | Fill type  |
| 1      | 3              | ?         | Reserved   |
| 4      | 4              | `u32`     | ARGB color |

### Image fills

| Offset | Length (bytes) | Data Type | Field     |
| ------ | -------------- | --------- | --------- |
| 0      | 1              | `0x03`    | Fill type |
| 1      | 3              | ?         | Reserved  |
| 4      | 4              | `u32`     | `a` (ID)  |
| 8      | 4              | `u32`     | `b` (ID)  |
| 12     | 4              | `u32`     | `c` (ID)  |
| 16     | 4              | `u32`     | `d` (ID)  |
| 20     | 4              | `f32`     | Opacity   |
| 24     | 4              | `width`   | Opacity   |
| 29     | 4              | `height`  | Opacity   |

### Gradient fills

| Offset | Length (bytes) | Data Type   | Field       |
| ------ | -------------- | ----------- | ----------- |
| 0      | 1              | `0x03`      | Fill type\* |
| 1      | 3              | ?           | Reserved    |
| 4      | 4              | `f32`       | Start `x`   |
| 8      | 4              | `f32`       | Start `y`   |
| 12     | 4              | `f32`       | End `x`     |
| 16     | 4              | `f32`       | End `y`     |
| 20     | 4              | `f32`       | Opacity     |
| 24     | 4              | `f32`       | Width\*\*   |
| 28     | 4              | `u8`        | Stop count  |
| 29     | 3              | ?           | Reserved    |
| 32     | 128            | _See below_ | Stop data   |

\*: **Fill type** is `0x01` for linear gradients and `0x02` for radial gradients.

\*\*: **Width** is unused in linear gradients.

#### Gradient stop data

Gradient stops are serialized as a sequence of `16` chunks with the following layout:

| Offset | Length (bytes) | Data Type | Field       |
| ------ | -------------- | --------- | ----------- |
| 0      | 4              | `u32`     | ARGB Color  |
| 4      | 4              | `f32`     | Stop offset |

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

### Flex Direction

| Value | Field         |
| ----- | ------------- |
| 0     | Row           |
| 1     | RowReverse    |
| 2     | Column        |
| 3     | ColumnReverse |
| \_    | error         |

### Grid Direction

| Value | Field  |
| ----- | ------ |
| 0     | Row    |
| 1     | Column |
| \_    | error  |

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

### Align Self

| Value | Field   |
| ----- | ------- |
| 0     | Auto    |
| 1     | Start   |
| 2     | End     |
| 3     | Center  |
| 4     | Stretch |
| \_    | error   |

### Justify Self

| Value | Field   |
| ----- | ------- |
| 0     | Auto    |
| 1     | Start   |
| 2     | End     |
| 3     | Center  |
| 4     | Stretch |
| \_    | error   |

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

### Grid Track Type

| Value | Field   |
| ----- | ------- |
| 0     | Percent |
| 1     | Flex    |
| 2     | Auto    |
| 3     | Fixed   |
| \_    | error   |

## Font

### Style

| Value | Variant |
| ----- | ------- |
| 0     | Normal  |
| 1     | Italic  |
| \_    | Normal  |
