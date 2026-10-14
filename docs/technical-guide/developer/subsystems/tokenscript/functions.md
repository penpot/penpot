---
title: Built-in functions
desc: The functions tokenscript provides without schemas in Penpot's interpreter: math, rounding, aggregation, type checks and helpers.
---

# Built-in functions

These functions exist in every config (`functions/builtin-functions`).
Color functions such as `lighten` and `rgb` are schema functions; see
[Schemas](/technical-guide/developer/subsystems/tokenscript/schemas/#bundled-schemas).
A schema function with the same name takes precedence over a built-in
one. Function names are case insensitive.

## Units

Math functions keep the unit of their argument, or of the first
argument that has one:

```text
round(1.5px)    -> 2px
sqrt(16rem)     -> 4rem
pow(2px, 3)     -> 8px
min(10px, 5)    -> 5px
```

Only `sum` converts units. The other functions of several numbers
compare the plain numbers: `max(1px, 2rem)` is `2px`. `sign`, `atan2`
and `pi` return plain numbers.

## Math

| Function | | Example |
|---|---|---|
| `abs(x)` | | `abs(-5)` is `5` |
| `sign(x)` | `-1`, `0` or `1` | |
| `mod(a, b)` | Modulo, with the sign of `b` | `mod(-7, 3)` is `2` |
| `remainder(a, b)` | Remainder, with the sign of `a` | `remainder(-7, 3)` is `-1` |
| `pow(base, exponent)` | | `pow(2, 3)` is `8` |
| `sqrt(x)`, `cbrt(x)` | Square and cube root | `cbrt(27)` is `3` |
| `exp(x)`, `expm1(x)` | `e^x` and `e^x - 1` | |
| `pi()` | | |

## Rounding

| Function | | Example |
|---|---|---|
| `round(x)` | Half up, like JavaScript | `round(2.5)` is `3`, `round(-2.5)` is `-2` |
| `round_to(x, digits)` | | `round_to(3.14159, 2)` is `3.14` |
| `floor(x)`, `ceil(x)` | | |
| `trunc(x)` | Drops the fraction | `trunc(-1.9)` is `-1` |

## Aggregation

All take one or more numbers, except `sum`, which takes two or more.

| Function | | Example |
|---|---|---|
| `min(x, ...)`, `max(x, ...)` | | `min(3, 1, 2)` is `1` |
| `sum(x, ...)` | Converts the arguments to a common unit first | `sum(1rem, 16px)` is `32px` |
| `average(x, ...)` | | `average(2, 4, 6)` is `4` |
| `hypot(x, ...)` | Square root of the sum of squares | `hypot(3, 4)` is `5` |

## Logarithms

| Function | Argument |
|---|---|
| `log(x)`, `ln(x)` | Natural logarithm; `x > 0` |
| `log(x, base)` | `x > 0`, `base > 0` and not `1` |
| `log10(x)`, `log2(x)` | `x > 0` |
| `log1p(x)` | `ln(1 + x)`; `x >= -1` |

## Trigonometry

Angles are in radians.

| Function | Argument |
|---|---|
| `sin(x)`, `cos(x)`, `tan(x)`, `atan(x)` | |
| `asin(x)`, `acos(x)` | `-1 <= x <= 1` |
| `atan2(y, x)` | |
| `sinh(x)`, `cosh(x)`, `tanh(x)`, `asinh(x)` | |
| `acosh(x)` | `x >= 1` |
| `atanh(x)` | `-1 < x < 1` |

An argument outside its domain raises `:functions/argument-out-of-range`.

## Types

| Function | |
|---|---|
| `type(x)` | The lower case type name: `number`, `string`, `color.hex`, `list.implicit`. Numbers with units give the unit (`px`), structured tokens `token`. |
| `is_number(x)`, `is_number_with_unit(x)`, `is_string(x)`, `is_boolean(x)`, `is_null(x)`, `is_color(x)`, `is_list(x)`, `is_dictionary(x)`, `is_token(x)` | |

## Other

| Function | |
|---|---|
| `parse_int(string)`, `parse_int(string, radix)` | Like JavaScript `parseInt`; raises when nothing parses |
| `range(n)`, `range(start, end)` | The list of whole numbers from `start` (or 0) up to `end`, excluded; at most 1 000 000 |
| `linear-gradient(...)` | The arguments as the string `linear-gradient(a, b)` |

Calls to the CSS keywords `inside`, `outside`, `above`, `below`,
`left`, `right`, `top`, `bottom`, `before`, `after`, `between`,
`uppercase`, `lowercase`, `underline`, `none`, `innerShadow`,
`outerShadow` and `shadow` are not evaluated: `uppercase(a)` is the
string `uppercase(a)` (`lexer/uninterpreted-keywords`).
