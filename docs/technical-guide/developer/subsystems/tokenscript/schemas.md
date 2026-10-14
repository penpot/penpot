---
title: Schemas
desc: How tokenscript schemas add color spaces, functions, units and constants to Penpot's interpreter, how schema URIs resolve, and which schemas Penpot ships.
---

# Schemas

Color spaces, functions, units, token types and constants are data:
schemas that carry tokenscript scripts. A config holds one registry per
kind, and the interpreter runs the scripts of the registered schemas.

## Configs

```clojure
(require '[app.common.tokenscript.config :as config])

(-> (config/make-config)
    (config/register-schemas [{:uri "/api/v1/function/double/0/"
                               :schema {:name "Double"
                                        :type "function"
                                        :keyword "double"
                                        :script {:script "return {input}.get(0) * 2;"}}}]))
```

`config/make-config` has the hex color space and the
[built-in units](#units). `config/register-schemas` takes
`{:uri :schema}` maps, with string or keyword keys, and registers each by
the schema `:type`: `color`, `function`, `token` or `constants`.
`app.common.tokenscript/default-config` is `make-config` with the
[bundled schemas](#bundled-schemas).

A config is an immutable `Config` record; registering returns a new one.
Scripts are parsed once, when they are registered, and a malformed
schema raises `:config/invalid-specification`.

## URIs

Schemas are identified by path URIs and never fetched:

```text
/api/v1/<category>/<name>/<version>/
```

The category is `core`, `function` or `schema`. The version is `0`,
`0.1`, `0.1.2` or `latest`. A lookup tries the URI, then the shorter
versions, then the latest registered one: `0.0.1`, `0.0`, `0`,
`latest`.

Other tokenscript tools write URIs with a host
(`https://<host>/api/v1/core/rgb-color/0/`). The host is dropped when a
URI is registered or looked up (`schema-uri/normalize`), so their
schemas connect to the bundled ones.

## Color spaces

A color space defines the channels of `Color.<name>`, the functions
that create such colors (initializers) and the conversions from and to
other spaces.

```json
{
  "name": "HSL",
  "type": "color",
  "schema": {
    "type": "object",
    "properties": {
      "h": { "type": "number" },
      "s": { "type": "number" },
      "l": { "type": "number" }
    },
    "order": ["h", "s", "l"]
  },
  "initializers": [
    { "keyword": "hsl", "script": { "script": "./initializer.tokenscript" } }
  ],
  "conversions": [
    {
      "source": "/api/v1/core/srgb-color/0/",
      "target": "$self",
      "script": { "script": "./from-srgb.tokenscript" }
    }
  ]
}
```

| Key | |
|---|---|
| `name` | The sub type: `Color.HSL`, case insensitive. |
| `schema.properties` | The channels and their types: `number`, `string` or `color`. Assigning `c.h = ...` checks them. |
| `schema.order` | The channel order of the string form: `hsl(120, 1, 0.5)`. |
| `initializers` | `keyword(args)` creates a color. The script gets the arguments as the list `{input}` and returns the color. |
| `conversions` | Scripts from `source` to `target`; `$self` is the URI of this schema. The script gets the color as `{input}` and returns the color in the target space. |

An initializer reads its arguments by position, in the order of
`schema.order`:

```text
variable values: List = {input};
variable output: Color.HSL;
output.h = values.get(0);
output.s = values.get(1);
output.l = values.get(2);
if (values.length() > 3) [
  output.alpha = values.get(3);
];
return output;
```

A conversion reads the channels by name (`{input}.h`) and builds the
target color the same way.

`color.to.<name>()` converts a color, and so does
`interpreter/color->type`. When there is no direct conversion, the
shortest chain is found by a breadth first search over all conversions
(`registry/conversion-steps`, memoized per registry). Alpha is kept.

## Functions

A schema function is a script called by its keyword.

| Key | |
|---|---|
| `keyword` | The function name, case insensitive. |
| `input.properties` | The parameters. The call needs at least as many arguments as there are properties without `"optional": true`. |
| `script.script` | The script. It gets the arguments as the list `{input}`; returning null raises `:functions/function-returned-null`. |
| `requirements` | URIs of the schemas the script uses. Checked for the bundled schemas, see below. |

A schema function takes precedence over a built-in function or a color
initializer with the same name.

## Units

The unit registry starts with these units (`units/default-specs`):

| Unit | Type | Converts to |
|---|---|---|
| `px` | absolute | `rem` |
| `rem` | absolute | `px`, at 16px per rem |
| `s` | absolute | `ms` |
| `ms` | absolute | `s` |
| `%` | relative | |
| `ch` | absolute | |

A unit spec has a `name`, a `keyword` and a `type`, `absolute` or
`relative`. Its `conversions` work like those of color spaces, with
`{input}` a number with unit. A relative unit has a `to_absolute`
script, which gets `{relative_value}` and `{other_value}` and returns a
number: for `%`, `{other_value} * ({relative_value} / 100)`.

Unit specs are added with `registry/register` on the `:units` registry
of a config; `register-schemas` does not register them. A new keyword
also has to be in `lexer/supported-formats`, or it does not lex as a
unit.

## Token types

Token type schemas (`"type": "token"`) describe structured values and
validate resolved values; see
[Token validation](/technical-guide/developer/subsystems/tokenscript/validation/).

## Constants

A constants schema defines names that token values can use as bare
words:

```json
{
  "name": "CSS Hex Colors",
  "type": "constants",
  "inline": true,
  "values": { "red": "#FF0000", "blue": "#0000FF" }
}
```

- Values that are strings are evaluated as scripts, once per config:
  `"#FF0000"` is a color, `"10px"` a number with unit. Other values are
  taken as they are.
- Only token values see them, as predeclared variables (case
  insensitive). Schema scripts do not, and `{red}` is a reference to a
  token named `red`, not the constant.
- Schemas with `"inline": false` are ignored. When two schemas define a
  name, the one registered last wins.

## Bundled schemas

The schemas Penpot ships are in
[common/resources/tokenscript/schemas](https://github.com/penpot/penpot/tree/develop/common/resources/tokenscript/schemas):

| Directory | Contents | URI |
|---|---|---|
| `types/<name>/` | Color spaces: sRGB, HSL, OKLCH, P3, ... | `/api/v1/core/<name>/0/` |
| `functions/<name>/` | Functions: `lighten`, `darken`, `saturate`, `desaturate`, `mix`, `invert`, and the CSS color functions `rgb`, `rgba`, `hsl`, `hsla` | `/api/v1/function/<name>/0/` |
| `constants/<name>/` | CSS named colors | `/api/v1/constants/<name>/0/` |

Each directory holds a `schema.json` that references its scripts by
relative path (`"script": "./initializer.tokenscript"`). The color
spaces, the color functions and the named colors come from
`@tokens-studio/tokenscript-schemas` 0.4.0 (MPL-2.0) and are kept
unchanged, so they can be compared with that package; the `css-*`
functions are Penpot's.

`app.common.tokenscript.schemas/schemas` returns these schemas with the
script references replaced by the script text. Nothing is generated:

- On the JVM the files are read from the classpath on first use.
- In ClojureScript the `inline-json` macro reads them at compile time
  and embeds them in the build as a minified JSON string, decoded on
  first use. The macro fails the build when a script does not parse, a
  schema references one that is not shipped, or two functions share a
  keyword. Under shadow-cljs the files it reads are recorded like
  `shadow.resource/inline` does, so editing a schema recompiles the
  namespace.

`common-tests.tokenscript.schemas-test` runs the same checks on the JVM.

### CSS color functions

`rgb`, `rgba`, `hsl` and `hsla` are schema functions written in
tokenscript. They take precedence over the `rgb`/`hsl` initializers of
the color spaces and accept the CSS syntaxes:

```text
rgb(255, 0, 0)    rgb(255 0 0)    rgb(100%, 0%, 0%)    rgba(255, 0, 0, 50%)
hsl(0, 100%, 50%)    hsl(120deg 100% 50%)    hsla({color.primary}, 0.5)
```

RGB channels are clamped to 0-255 and rounded, alpha values are clamped
to 0-1 and an alpha of 1 is opaque. HSL saturation and lightness given
as plain numbers are fractions (`hsl(0, 1, 0.5)`, the tokenscript HSL
scale) unless one of them is above 1, in which case they are CSS
percentages (`hsl(0, 100, 50)`).

The slash alpha syntax (`rgb(255 0 0 / 50%)`) is not supported: in
tokenscript the slash is a division, evaluated before the function sees
its arguments.
