// This file contains hard-coded schemas that get used in tokenscript, so they can be bundled
// They enable colors / units / custom functions
// More schemas can be found in the schema registry: https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/schema/

export const COLOR_SCHEMAS = [
  {
    uri: "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/schema/hsl-color/0/",
    schema: {
      name: "HSL",
      type: "color",
      schema: {
        type: "object",
        order: ["h", "s", "l"],
        required: ["h", "s", "l"],
        properties: {
          h: { type: "number" },
          l: { type: "number" },
          s: { type: "number" },
        },
        additionalProperties: false,
      },
      conversions: [
        {
          script: {
            type: "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/core/tokenscript/0/",
            script:
              "variable rgb: List = {input}.r, {input}.g, {input}.b;\nvariable hsl: List = 0, 0, 0;\n\n// Step 1: Normalize RGB values to [0,1] range\n// Assume input is either already normalized (0-1) or in [0-255] range\nvariable r: Number = rgb.get(0);\nvariable g: Number = rgb.get(1); \nvariable b: Number = rgb.get(2);\n\n// If any component > 1, assume 0-255 range and normalize\nif (r > 1 || g > 1 || b > 1) [\n    r = r / 255;\n    g = g / 255;\n    b = b / 255;\n];\n\n// Step 2: Find min and max values\nvariable max_val: Number = max(max(r, g), b);\nvariable min_val: Number = min(min(r, g), b);\nvariable delta: Number = max_val - min_val;\n\n// Step 3: Calculate Lightness (0-1, will convert to 0-100 later)\nvariable lightness: Number = (max_val + min_val) / 2;\n\n// Step 4: Calculate Saturation\nvariable saturation: Number = 0;\nif (delta > 0) [\n    if (lightness <= 0.5) [\n        saturation = delta / (max_val + min_val);\n    ] else [\n        saturation = delta / (2 - max_val - min_val);\n    ];\n];\n\n// Step 5: Calculate Hue (in degrees)\nvariable hue: Number = 0;\nif (delta > 0) [\n    if (max_val == r) [\n        variable hue_segment: Number = (g - b) / delta;\n        hue = 60 * hue_segment;\n    ] else [\n        if (max_val == g) [\n            hue = 60 * ((b - r) / delta + 2);\n        ] else [\n            hue = 60 * ((r - g) / delta + 4);\n        ];\n    ];\n];\n\n// Ensure hue is in [0, 360) range\nif (hue < 0) [\n    hue = hue + 360;\n];\n\n// Convert to final ranges: H (0-360), S (0-100), L (0-100)\nhsl.update(0, hue);\nhsl.update(1, saturation * 100);\nhsl.update(2, lightness * 100);\n\nvariable output: Color.HSL;\noutput.h = hsl.get(0);\noutput.s = hsl.get(1);\noutput.l = hsl.get(2);\nreturn output;",
          },
          source:
            "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/schema/srgb-color/0/",
          target:
            "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/schema/hsl-color/0/",
          lossless: true,
          description: "Converts RGB to HSL using standard algorithm",
        },
        {
          script: {
            type: "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/core/tokenscript/0/",
            script:
              "variable hsl: List = {input}.h, {input}.s, {input}.l;\nvariable rgb: List = 0, 0, 0;\nvariable H: Number = hsl.get(0);\nvariable S: Number = hsl.get(1) / 100;  // Convert percentage to [0,1]\nvariable L: Number = hsl.get(2) / 100;  // Convert percentage to [0,1]\n\n// Step 1: Calculate chroma\nvariable C: Number = (1 - abs(2 * L - 1)) * S;\n\n// Step 2: Calculate hue sector (H' = H / 60)\nvariable H_prime: Number = H / 60;\n\n// Step 3: Calculate intermediate value X\n// X = C * (1 - |H' mod 2 - 1|)\n// Since we can't use mod, we'll implement it with conditional logic\nvariable H_mod: Number = H_prime;\nif (H_prime >= 2) [\n    H_mod = H_prime - 2;\n];\nif (H_prime >= 4) [\n    H_mod = H_prime - 4;\n];\nvariable X: Number = C * (1 - abs(H_mod - 1));\n\n// Step 4: Map (C, X, 0) to RGB' based on hue sector\nvariable R1: Number = 0;\nvariable G1: Number = 0;\nvariable B1: Number = 0;\n\nif (H_prime >= 0 && H_prime < 1) [      // 0° ≤ H < 60°\n    R1 = C;\n    G1 = X;\n    B1 = 0;\n] else [\n    if (H_prime >= 1 && H_prime < 2) [  // 60° ≤ H < 120°\n        R1 = X;\n        G1 = C;\n        B1 = 0;\n    ] else [\n        if (H_prime >= 2 && H_prime < 3) [  // 120° ≤ H < 180°\n            R1 = 0;\n            G1 = C;\n            B1 = X;\n        ] else [\n            if (H_prime >= 3 && H_prime < 4) [  // 180° ≤ H < 240°\n                R1 = 0;\n                G1 = X;\n                B1 = C;\n            ] else [\n                if (H_prime >= 4 && H_prime < 5) [  // 240° ≤ H < 300°\n                    R1 = X;\n                    G1 = 0;\n                    B1 = C;\n                ] else [                             // 300° ≤ H < 360°\n                    R1 = C;\n                    G1 = 0;\n                    B1 = X;\n                ];\n            ];\n        ];\n    ];\n];\n\n// Step 5: Calculate match value and final RGB\nvariable m: Number = L - C / 2;\nvariable R: Number = (R1 + m) * 255;\nvariable G: Number = (G1 + m) * 255;\nvariable B: Number = (B1 + m) * 255;\n\n// Ensure RGB values are in [0,255] range\nif (R < 0) [\n    R = 0;\n];\nif (R > 255) [\n    R = 255;\n];\nif (G < 0) [\n    G = 0;\n];\nif (G > 255) [\n    G = 255;\n];\nif (B < 0) [\n    B = 0;\n];\nif (B > 255) [\n    B = 255;\n];\n\nrgb.update(0, R);\nrgb.update(1, G);\nrgb.update(2, B);\n\nvariable output: Color.SRGB;\noutput.r = rgb.get(0);\noutput.g = rgb.get(1);\noutput.b = rgb.get(2);\nreturn output;",
          },
          source:
            "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/schema/hsl-color/0/",
          target:
            "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/schema/srgb-color/0/",
          lossless: true,
          description: "Converts HSL to RGB using standard algorithm",
        },
      ],
      description:
        "HSL (Hue, Saturation, Lightness) color space - cylindrical representation of RGB",
      initializers: [
        {
          title: "function",
          schema: {
            type: "string",
            pattern: "^hsl\\((\\d{1,3}),\\s*(\\d{1,3}),\\s*(\\d{1,3})\\)$",
          },
          script: {
            type: "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/core/tokenscript/0/",
            script:
              "variable color_parts: List = {input};\nvariable output: Color.HSL;\noutput.h = color_parts.get(0);\noutput.s = color_parts.get(1);\noutput.l = color_parts.get(2);\nreturn output;",
          },
          keyword: "hsl",
          description: "Creates a HSL color from string",
        },
      ],
    },
  },
  {
    uri: "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/schema/srgb-color/0/",
    schema: {
      name: "SRGB",
      type: "color",
      schema: {
        type: "object",
        order: ["r", "g", "b"],
        required: ["r", "g", "b"],
        properties: {
          b: { type: "number" },
          g: { type: "number" },
          r: { type: "number" },
        },
        additionalProperties: false,
      },
      conversions: [
        {
          script: {
            type: "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/core/tokenscript/0/",
            script:
              " variable color_parts: List = {input}.to_string().split('#'); \n variable color: List = color_parts.get(1).split(); \n variable length: Number = color.length(); \n variable rgb: List = 0, 0, 0; \n if(length == 3) [ \n rgb.update(0, parse_int(color.get(0).concat(color.get(0)), 16)); \n rgb.update(1, parse_int(color.get(1).concat(color.get(1)), 16)); \n rgb.update(2, parse_int(color.get(2).concat(color.get(2)), 16)); \n ] else [ \n rgb.update(0, parse_int(color.get(0).concat(color.get(1)), 16)); \n rgb.update(1, parse_int(color.get(2).concat(color.get(3)), 16)); \n rgb.update(2, parse_int(color.get(4).concat(color.get(5)), 16)); \n ]; \n \n variable output: Color.SRGB; \n output.r = rgb.get(0); \n output.g = rgb.get(1); \n output.b = rgb.get(2); \n \n return output; \n",
          },
          source:
            "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/core/hex-color/0/",
          target: "$self",
          lossless: true,
          description: "Converts HEX to RGB",
        },
        {
          script: {
            type: "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/core/tokenscript/0/",
            script:
              'variable rgba: List = {input}.r, {input}.g, {input}.b;\n    variable hex: String = "#";\n    variable i: Number = 0;\n    variable value: Number = 0;\n    // Convert RGBA to Hex\n    while( i < min(rgba.length(), 3)) [\n        value = round(rgba.get(i));\n        if(value < 16) [\n            hex = hex.concat("0").concat(value.to_string(16));\n        ] else [\n            hex = hex.concat(value.to_string(16));\n        ];\n        i = i + 1;\n    ];\n    \n    if (rgba.length() == 4) [\n        value = rgba.get(3) * 255; // Convert alpha to 0-255 range\n        if(value < 16) [\n            hex = hex.concat("0").concat(value.to_string(16));\n        ] else [\n            hex = hex.concat(value.to_string(16));\n        ];\n    ];\n    \n    return hex;',
          },
          source: "$self",
          target:
            "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/core/hex-color/0/",
          lossless: true,
          description: "Converts RGB to HEX",
        },
      ],
      description: "RGB color",
      initializers: [
        {
          title: "function",
          schema: {
            type: "string",
            pattern: "^rgb\\((\\d{1,3}),\\s*(\\d{1,3}),\\s*(\\d{1,3})\\)$",
          },
          script: {
            type: "https://schema.tokenscript.dev.gcp.tokens.studio/api/v1/core/tokenscript/0/",
            script:
              "variable color_parts: List = {input}; \n variable output: Color.SRGB;\n output.r = color_parts.get(0);\n output.g = color_parts.get(1);\n output.b = color_parts.get(2);\n return output;",
          },
          keyword: "srgb",
          description: "Creates a RGB color from string",
        },
      ],
    },
  },
];
