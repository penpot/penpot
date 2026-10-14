---
title: Language
desc: The tokenscript language as Penpot's interpreter implements it: inline and script mode, values, references, units, operators, template strings, statements and methods.
---

# Tokenscript language

A token value is a tokenscript expression; schema scripts are small
tokenscript programs. This page describes the language as
`app.common.tokenscript` implements it. Built-in functions are listed in
[Built-in functions](/technical-guide/developer/subsystems/tokenscript/functions/).

## Inline and script mode

The parser has two modes (`parser/parse` with `:mode`):

| | Inline mode | Script mode |
|---|---|---|
| Used for | Token values | Schema scripts, constants, `processor/resolve-value` |
| Parses | One expression, which may be a list | Statements separated by `;` |
| Bare words | Greedy: `http://foo.bar` is one string | Stop at `.`, `:`, `/` and operators |

A token value that starts with a statement keyword (`variable`, `if`,
`while`, `for`, `return`) fails in inline mode and is parsed again in
script mode, so a token value can be a small program:

```text
variable c: Color = {color.primary}; c.to.hsl()
```

## Values

| Type | Written as |
|---|---|
| `Number` | `42`, `3.14`, `.5` |
| `NumberWithUnit` | `16px`, `50%`, `(1 + 2)px`, `{size}px` |
| `String` | `"bold"`, `'bold'`, `bold`, `` `text {ref}` `` |
| `Boolean` | `true`, `false` |
| `Null` | `null` |
| `Color` | `#f00`, `#ff000080`, `hsl(120, 1, 0.5)`; the sub type is the color space: `Color.Hex`, `Color.HSL` |
| `List` | `a, b` and `a b` (see [Bare words and lists](#bare-words-and-lists)), `[1, 2]` in scripts |
| `Dictionary` | The value of a group reference (`{color}`), or `variable d: Dictionary;` |
| `Token` | A structured token value: typography, shadow |

Names are case insensitive: variables, functions, methods, types, color
spaces and units. `//` starts a comment that runs to the end of the
line.

## References

`{color.primary}` is the value of another token. Whitespace inside the
braces is ignored. A reference can also name a group, a field of a
structured token or a key of a dictionary token; see
[Resolver](/technical-guide/developer/subsystems/tokenscript/resolver/#references).

Bare words are never references: `base * 2` is the string `base` times
2, which fails.

## Bare words and lists

A word that is not a keyword is a string: `bold`, `my-value`. Quote
strings whenever the value is more than a simple word.

Expressions next to each other form an implicit list, rendered with
spaces; commas form a list rendered with `, `:

```text
1px solid black   -> [1px, "solid", "black"]   renders "1px solid black"
Arial, serif      -> ["Arial", "serif"]        renders "Arial, serif"
1 + 2 3           -> [3, 3]
```

A number followed by a word that is not a unit is a list too:
`1unknown` is `[1, "unknown"]` and `3D Font` is `[3, "D", "Font"]`. A
number with two decimal points (`3.3.3`) is a lexer error.

In inline mode bare words are greedy: they run until whitespace or one
of `{ } ( ) [ ] , " ' ;`. URLs and dotted names need no quotes, and an
operator needs spaces around it:

```text
http://fonts.example.com   -> "http://fonts.example.com"
foo+bar                    -> "foo+bar"
foo + bar                  -> String + String, an error
```

A dot chain after a value is one word too: in a token value
`{c}.to.hsl()` calls a method named `to.hsl`, which does not exist.
Single attributes and methods (`{c}.l`, `{c}.to_string()`) work; for a
conversion, write the value as a script as shown in
[Inline and script mode](#inline-and-script-mode).

## Units

The lexer knows the units `px`, `em`, `rem`, `vw`, `vh`, `pt`, `in`,
`cm`, `mm`, `deg`, `%`, `s`, `ms` and `ch`
(`lexer/supported-formats`). A unit is only a unit directly after a
number, a closing parenthesis or a reference:

| Input | Value |
|---|---|
| `3px` | `3px` |
| `3 px` | the list `[3, "px"]` |
| `(3 + 4)px` | `7px` |
| `{size}rem` | the number of `size` with the unit `rem` |
| `output.s` | the attribute `s` of `output` |

So a variable named `s` and the attribute `.s` of an HSL color do not
clash with the unit `s`.

Conversions between units come from the unit registry: `rem` and `px`
(16px per rem), `s` and `ms`. The other units have none; see
[Schemas](/technical-guide/developer/subsystems/tokenscript/schemas/#units).

## Operators

From the tightest binding to the loosest; operators of one row are
evaluated left to right:

| Operators | |
|---|---|
| `-x` `+x` `!x` | Unary |
| `^` | Power: `2 ^ 3 ^ 2` is `64`, `-2 ^ 2` is `4` |
| `*` `/` | |
| `+` `-` | |
| `==` `!=` `>` `<` `>=` `<=` | Comparison |
| `&&` `\|\|` | Same precedence: `true \|\| false && false` is `false` |

Operands are not coerced:

- Arithmetic takes numbers and numbers with units. Strings are joined
  with `.concat()`.
- Comparisons of different types (`1 == "1"`) and of different units
  (`1px < 2rem`) raise an error. `>`, `<`, `>=` and `<=` raise on null.
- `&&`, `||`, `!` and the conditions of `if` and `while` take booleans
  only.
- Lists and dictionaries are equal only to themselves.

### Arithmetic with units

Units are tags on a number: `3px * 3px` is `9px`. A unit and a plain
number keep the unit: `2px + 1` is `3px`.

| Operation | Rule | Example |
|---|---|---|
| `+` `-` with two convertible units | Both are converted to the unit giving the larger magnitude | `1rem + 16px` is `32px`, `1s + 500ms` is `1500ms` |
| `+` `-` with two units without a conversion | Error | `1in + 1cm` |
| `+` `-` with one `%` | The percentage of the other operand | `50% + 200px` is `300px`, `100 + 10%` is `110` |
| `*` `/` with one `%` | The fraction of the other operand, with its unit | `50% * 200px` is `100px`, `50% * 2` is `1` |
| `*` `/` with two `%` | Tag arithmetic | `50% * 50%` is `2500%` |
| `^` with two units | Error | `2px ^ 2px` |

`%` is the only relative unit; its rule is the `to_absolute` script of
its unit schema. Division by zero raises an error.

## Template strings

Backticks build a string from text, references and expressions:

```text
`border: {border.width} solid {border.color}`
`width: ${ {spacing.base} * 2 }px`
```

- `{ref}` inserts a reference; `${expr}` inserts any expression.
- `\{`, `\${`, `` \` `` and `\\` insert the characters literally.
- A template with only one reference or expression keeps its type:
  `` `{count}` `` is a number.
- Lists, dictionaries and colors other than hex colors cannot be
  inserted (`:interpreter/template-invalid-type`).
- Template strings cannot be nested inside `${...}`.

## Statements

Scripts are statements separated by `;`. Inside a block (`[ ... ]`)
end every statement with `;`: an expression directly before `]` does
not parse.

```text
variable hsl_values: List = {input};
variable output: Color.HSL;

output.h = hsl_values.get(0);
output.s = hsl_values.get(1);
output.l = hsl_values.get(2);

if (hsl_values.length() > 3) [
  output.alpha = hsl_values.get(3);
];

return output;
```

| Statement | |
|---|---|
| `variable name: Type = value;` | Declares a variable; without `= value` it holds the null value of its type. Types: `Number`, `String`, `Boolean`, `NumberWithUnit`, `Color` and `Color.<space>`, `Null`, `List`, `Dictionary`, `Token`. |
| `name = value;` | Reassigns; the value must have the declared type. |
| `color.channel = value;` | Sets a channel of a color, checked against the color space schema; `alpha` takes a number from 0 to 1 or null. |
| `if (c) [ ... ] elif (c) [ ... ] else [ ... ];` | |
| `while (c) [ ... ];` | Raises after 1000 iterations (`:max-iterations` in the config's `:language-options`). |
| `for item in list [ ... ];` | Evaluates to the list of the values of the body. `for item, index in list` also binds the index. The loop variables must not exist before and are removed after the loop. |
| `return value;` | Ends the script with `value`. |

Without `return`, a script evaluates to the value of its last statement
that has one.

Scope is flat: a block does not open a scope, and a variable declared in
a block is visible after it. Declaring a name twice raises
`:interpreter/variable-already-defined`.

In token values the [constants](/technical-guide/developer/subsystems/tokenscript/schemas/#constants)
are predeclared variables: `variable red: Number = 1;` fails there.

## Attributes and methods

`x.name` reads an attribute, `x.name(args)` calls a method.

Attributes: `value` of numbers and numbers with units (a plain number),
the channels and `alpha` of colors, the keys of dictionaries, the keys
and `type` / `subType` of tokens.

| Type | Methods |
|---|---|
| `Number` | `to_string()`, `to_string(radix)` |
| `NumberWithUnit` | `to_string()`, `to_number()` |
| `String` | `upper()`, `lower()`, `length()`, `concat(s)`, `split()`, `split(separator)`, `trim()` |
| `Color` | `to_string()`, `to.<space>()` (`#ff0000.to.hsl()`) |
| `List` | `append(x)`, `extend(x, ...)`, `insert(index, x)`, `delete(index)`, `update(index, x)`, `get(index)`, `index(x)`, `length()`, `join()`, `join(separator)` |
| `Dictionary` | `get(key)`, `set(key, value)`, `delete(key)`, `keys()`, `values()`, `key_exists(key)`, `length()`, `clear()` |
| `Token` | `get(key or index)`, `set(key, value)`, `keys()`, `values()`, `length()`, and the list methods for tokens holding a list |

`get` takes negative indexes from the end. Methods that change a list or
dictionary change it in place; inserted values are copied, so a value
appended twice is two independent values.

`to.<space>()` converts a color to any registered color space,
chaining conversions when there is no direct one; see
[Schemas](/technical-guide/developer/subsystems/tokenscript/schemas/#color-spaces).

## Tolerant parsing

For editors, `(parser/parse text {:mode :inline :tolerant? true})`
returns a partial AST instead of raising on incomplete input. Unclosed
references, strings, parentheses and function calls, missing operands
and trailing dots are listed in `:incomplete`, and the AST holds partial
nodes (`:partial-reference`, `:partial-function-call`, ...; see
`app.common.tokenscript.ast`). Tolerant parsing is inline only.
