# Serialization

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
