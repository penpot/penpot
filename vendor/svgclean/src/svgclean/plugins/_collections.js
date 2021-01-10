'use strict';

// http://www.w3.org/TR/SVG11/intro.html#Definitions
exports.elemsGroups = {
    animation: ['animate', 'animateColor', 'animateMotion', 'animateTransform', 'set'],
    descriptive: ['desc', 'metadata', 'title'],
    shape: ['circle', 'ellipse', 'line', 'path', 'polygon', 'polyline', 'rect'],
    structural: ['defs', 'g', 'svg', 'symbol', 'use'],
    paintServer: ['solidColor', 'linearGradient', 'radialGradient', 'meshGradient', 'pattern', 'hatch'],
    nonRendering: ['linearGradient', 'radialGradient', 'pattern', 'clipPath', 'mask', 'marker', 'symbol', 'filter', 'solidColor'],
    container: ['a', 'defs', 'g', 'marker', 'mask', 'missing-glyph', 'pattern', 'svg', 'switch', 'symbol', 'foreignObject'],
    textContent: ['altGlyph', 'altGlyphDef', 'altGlyphItem', 'glyph', 'glyphRef', 'textPath', 'text', 'tref', 'tspan'],
    textContentChild: ['altGlyph', 'textPath', 'tref', 'tspan'],
    lightSource: ['feDiffuseLighting', 'feSpecularLighting', 'feDistantLight', 'fePointLight', 'feSpotLight'],
    filterPrimitive: ['feBlend', 'feColorMatrix', 'feComponentTransfer', 'feComposite', 'feConvolveMatrix', 'feDiffuseLighting', 'feDisplacementMap', 'feFlood', 'feGaussianBlur', 'feImage', 'feMerge', 'feMorphology', 'feOffset', 'feSpecularLighting', 'feTile', 'feTurbulence']
};

exports.pathElems = ['path', 'glyph', 'missing-glyph'];

// http://www.w3.org/TR/SVG11/intro.html#Definitions
exports.attrsGroups = {
    animationAddition: ['additive', 'accumulate'],
    animationAttributeTarget: ['attributeType', 'attributeName'],
    animationEvent: ['onbegin', 'onend', 'onrepeat', 'onload'],
    animationTiming: ['begin', 'dur', 'end', 'min', 'max', 'restart', 'repeatCount', 'repeatDur', 'fill'],
    animationValue: ['calcMode', 'values', 'keyTimes', 'keySplines', 'from', 'to', 'by'],
    conditionalProcessing: ['requiredFeatures', 'requiredExtensions', 'systemLanguage'],
    core: ['id', 'tabindex', 'xml:base', 'xml:lang', 'xml:space'],
    graphicalEvent: ['onfocusin', 'onfocusout', 'onactivate', 'onclick', 'onmousedown', 'onmouseup', 'onmouseover', 'onmousemove', 'onmouseout', 'onload'],
    presentation: [
        'alignment-baseline',
        'baseline-shift',
        'clip',
        'clip-path',
        'clip-rule',
        'color',
        'color-interpolation',
        'color-interpolation-filters',
        'color-profile',
        'color-rendering',
        'cursor',
        'direction',
        'display',
        'dominant-baseline',
        'enable-background',
        'fill',
        'fill-opacity',
        'fill-rule',
        'filter',
        'flood-color',
        'flood-opacity',
        'font-family',
        'font-size',
        'font-size-adjust',
        'font-stretch',
        'font-style',
        'font-variant',
        'font-weight',
        'glyph-orientation-horizontal',
        'glyph-orientation-vertical',
        'image-rendering',
        'letter-spacing',
        'lighting-color',
        'marker-end',
        'marker-mid',
        'marker-start',
        'mask',
        'opacity',
        'overflow',
        'paint-order',
        'pointer-events',
        'shape-rendering',
        'stop-color',
        'stop-opacity',
        'stroke',
        'stroke-dasharray',
        'stroke-dashoffset',
        'stroke-linecap',
        'stroke-linejoin',
        'stroke-miterlimit',
        'stroke-opacity',
        'stroke-width',
        'text-anchor',
        'text-decoration',
        'text-overflow',
        'text-rendering',
        'transform',
        'unicode-bidi',
        'vector-effect',
        'visibility',
        'word-spacing',
        'writing-mode'
    ],
    xlink: ['xlink:href', 'xlink:show', 'xlink:actuate', 'xlink:type', 'xlink:role', 'xlink:arcrole', 'xlink:title'],
    documentEvent: ['onunload', 'onabort', 'onerror', 'onresize', 'onscroll', 'onzoom'],
    filterPrimitive: ['x', 'y', 'width', 'height', 'result'],
    transferFunction: ['type', 'tableValues', 'slope', 'intercept', 'amplitude', 'exponent', 'offset']
};

exports.attrsGroupsDefaults = {
    core: {'xml:space': 'preserve'},
    filterPrimitive: {x: '0', y: '0', width: '100%', height: '100%'},
    presentation: {
        clip: 'auto',
        'clip-path': 'none',
        'clip-rule': 'nonzero',
        mask: 'none',
        opacity: '1',
        'stop-color': '#000',
        'stop-opacity': '1',
        'fill-opacity': '1',
        'fill-rule': 'nonzero',
        fill: '#000',
        stroke: 'none',
        'stroke-width': '1',
        'stroke-linecap': 'butt',
        'stroke-linejoin': 'miter',
        'stroke-miterlimit': '4',
        'stroke-dasharray': 'none',
        'stroke-dashoffset': '0',
        'stroke-opacity': '1',
        'paint-order': 'normal',
        'vector-effect': 'none',
        display: 'inline',
        visibility: 'visible',
        'marker-start': 'none',
        'marker-mid': 'none',
        'marker-end': 'none',
        'color-interpolation': 'sRGB',
        'color-interpolation-filters': 'linearRGB',
        'color-rendering': 'auto',
        'shape-rendering': 'auto',
        'text-rendering': 'auto',
        'image-rendering': 'auto',
        'font-style': 'normal',
        'font-variant': 'normal',
        'font-weight': 'normal',
        'font-stretch': 'normal',
        'font-size': 'medium',
        'font-size-adjust': 'none',
        kerning: 'auto',
        'letter-spacing': 'normal',
        'word-spacing': 'normal',
        'text-decoration': 'none',
        'text-anchor': 'start',
        'text-overflow': 'clip',
        'writing-mode': 'lr-tb',
        'glyph-orientation-vertical': 'auto',
        'glyph-orientation-horizontal': '0deg',
        direction: 'ltr',
        'unicode-bidi': 'normal',
        'dominant-baseline': 'auto',
        'alignment-baseline': 'baseline',
        'baseline-shift': 'baseline'
    },
    transferFunction: {slope: '1', intercept: '0', amplitude: '1', exponent: '1', offset: '0'}
};

// http://www.w3.org/TR/SVG11/eltindex.html
exports.elems = {
    a: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation',
            'xlink'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'transform',
            'target'
        ],
        defaults: {
            target: '_self'
        },
        contentGroups: [
            'animation',
            'descriptive',
            'shape',
            'structural',
            'paintServer'
        ],
        content: [
            'a',
            'altGlyphDef',
            'clipPath',
            'color-profile',
            'cursor',
            'filter',
            'font',
            'font-face',
            'foreignObject',
            'image',
            'marker',
            'mask',
            'pattern',
            'script',
            'style',
            'switch',
            'text',
            'view'
        ]
    },
    altGlyph: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation',
            'xlink'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'x',
            'y',
            'dx',
            'dy',
            'glyphRef',
            'format',
            'rotate'
        ]
    },
    altGlyphDef: {
        attrsGroups: [
            'core'
        ],
        content: [
            'glyphRef'
        ]
    },
    altGlyphItem: {
        attrsGroups: [
            'core'
        ],
        content: [
            'glyphRef',
            'altGlyphItem'
        ]
    },
    animate: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'animationAddition',
            'animationAttributeTarget',
            'animationEvent',
            'animationTiming',
            'animationValue',
            'presentation',
            'xlink'
        ],
        attrs: [
            'externalResourcesRequired'
        ],
        contentGroups: [
            'descriptive'
        ]
    },
    animateColor: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'animationEvent',
            'xlink',
            'animationAttributeTarget',
            'animationTiming',
            'animationValue',
            'animationAddition',
            'presentation'
        ],
        attrs: [
            'externalResourcesRequired'
        ],
        contentGroups: [
            'descriptive'
        ]
    },
    animateMotion: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'animationEvent',
            'xlink',
            'animationTiming',
            'animationValue',
            'animationAddition'
        ],
        attrs: [
            'externalResourcesRequired',
            'path',
            'keyPoints',
            'rotate',
            'origin'
        ],
        defaults: {
            'rotate': '0'
        },
        contentGroups: [
            'descriptive'
        ],
        content: [
            'mpath'
        ]
    },
    animateTransform: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'animationEvent',
            'xlink',
            'animationAttributeTarget',
            'animationTiming',
            'animationValue',
            'animationAddition'
        ],
        attrs: [
            'externalResourcesRequired',
            'type'
        ],
        contentGroups: [
            'descriptive'
        ]
    },
    circle: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'transform',
            'cx',
            'cy',
            'r'
        ],
        defaults: {
            cx: '0',
            cy: '0'
        },
        contentGroups: [
            'animation',
            'descriptive'
        ]
    },
    clipPath: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'transform',
            'clipPathUnits'
        ],
        defaults: {
            clipPathUnits: 'userSpaceOnUse'
        },
        contentGroups: [
            'animation',
            'descriptive',
            'shape'
        ],
        content: [
            'text',
            'use'
        ]
    },
    'color-profile': {
        attrsGroups: [
            'core',
            'xlink'
        ],
        attrs: [
            'local',
            'name',
            'rendering-intent'
        ],
        defaults: {
            name: 'sRGB',
            'rendering-intent': 'auto'
        },
        contentGroups: [
            'descriptive'
        ]
    },
    cursor: {
        attrsGroups: [
            'core',
            'conditionalProcessing',
            'xlink'
        ],
        attrs: [
            'externalResourcesRequired',
            'x',
            'y'
        ],
        defaults: {
            x: '0',
            y: '0'
        },
        contentGroups: [
            'descriptive'
        ]
    },
    defs: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'transform'
        ],
        contentGroups: [
            'animation',
            'descriptive',
            'shape',
            'structural',
            'paintServer'
        ],
        content: [
            'a',
            'altGlyphDef',
            'clipPath',
            'color-profile',
            'cursor',
            'filter',
            'font',
            'font-face',
            'foreignObject',
            'image',
            'marker',
            'mask',
            'pattern',
            'script',
            'style',
            'switch',
            'text',
            'view'
        ]
    },
    desc: {
        attrsGroups: [
            'core'
        ],
        attrs: [
            'class',
            'style'
        ]
    },
    ellipse: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'transform',
            'cx',
            'cy',
            'rx',
            'ry'
        ],
        defaults: {
            cx: '0',
            cy: '0'
        },
        contentGroups: [
            'animation',
            'descriptive'
        ]
    },
    feBlend: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive'
        ],
        attrs: [
            'class',
            'style',
            // TODO: in - 'If no value is provided and this is the first filter primitive,
            // then this filter primitive will use SourceGraphic as its input'
            'in',
            'in2',
            'mode'
        ],
        defaults: {
            mode: 'normal'
        },
        content: [
            'animate',
            'set'
        ]
    },
    feColorMatrix: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive'
        ],
        attrs: [
            'class',
            'style',
            'in',
            'type',
            'values'
        ],
        defaults: {
            type: 'matrix'
        },
        content: [
            'animate',
            'set'
        ]
    },
    feComponentTransfer: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive'
        ],
        attrs: [
            'class',
            'style',
            'in'
        ],
        content: [
            'feFuncA',
            'feFuncB',
            'feFuncG',
            'feFuncR'
        ]
    },
    feComposite: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive'
        ],
        attrs: [
            'class',
            'style',
            'in',
            'in2',
            'operator',
            'k1',
            'k2',
            'k3',
            'k4'
        ],
        defaults: {
            operator: 'over',
            k1: '0',
            k2: '0',
            k3: '0',
            k4: '0'
        },
        content: [
            'animate',
            'set'
        ]
    },
    feConvolveMatrix: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive'
        ],
        attrs: [
            'class',
            'style',
            'in',
            'order',
            'kernelMatrix',
            // TODO: divisor - 'The default value is the sum of all values in kernelMatrix,
            // with the exception that if the sum is zero, then the divisor is set to 1'
            'divisor',
            'bias',
            // TODO: targetX - 'By default, the convolution matrix is centered in X over each
            // pixel of the input image (i.e., targetX = floor ( orderX / 2 ))'
            'targetX',
            'targetY',
            'edgeMode',
            // TODO: kernelUnitLength - 'The first number is the <dx> value. The second number
            // is the <dy> value. If the <dy> value is not specified, it defaults to the same value as <dx>'
            'kernelUnitLength',
            'preserveAlpha'
        ],
        defaults: {
            order: '3',
            bias: '0',
            edgeMode: 'duplicate',
            preserveAlpha: 'false'
        },
        content: [
            'animate',
            'set'
        ]
    },
    feDiffuseLighting: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive'
        ],
        attrs: [
            'class',
            'style',
            'in',
            'surfaceScale',
            'diffuseConstant',
            'kernelUnitLength'
        ],
        defaults: {
            surfaceScale: '1',
            diffuseConstant: '1'
        },
        contentGroups: [
            'descriptive'
        ],
        content: [
            // TODO: 'exactly one light source element, in any order'
            'feDistantLight',
            'fePointLight',
            'feSpotLight'
        ]
    },
    feDisplacementMap: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive'
        ],
        attrs: [
            'class',
            'style',
            'in',
            'in2',
            'scale',
            'xChannelSelector',
            'yChannelSelector'
        ],
        defaults: {
            scale: '0',
            xChannelSelector: 'A',
            yChannelSelector: 'A'
        },
        content: [
            'animate',
            'set'
        ]
    },
    feDistantLight: {
        attrsGroups: [
            'core'
        ],
        attrs: [
            'azimuth',
            'elevation'
        ],
        defaults: {
            azimuth: '0',
            elevation: '0'
        },
        content: [
            'animate',
            'set'
        ]
    },
    feFlood: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive'
        ],
        attrs: [
            'class',
            'style'
        ],
        content: [
            'animate',
            'animateColor',
            'set'
        ]
    },
    feFuncA: {
        attrsGroups: [
            'core',
            'transferFunction'
        ],
        content: [
            'set',
            'animate'
        ]
    },
    feFuncB: {
        attrsGroups: [
            'core',
            'transferFunction'
        ],
        content: [
            'set',
            'animate'
        ]
    },
    feFuncG: {
        attrsGroups: [
            'core',
            'transferFunction'
        ],
        content: [
            'set',
            'animate'
        ]
    },
    feFuncR: {
        attrsGroups: [
            'core',
            'transferFunction'
        ],
        content: [
            'set',
            'animate'
        ]
    },
    feGaussianBlur: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive'
        ],
        attrs: [
            'class',
            'style',
            'in',
            'stdDeviation'
        ],
        defaults: {
            stdDeviation: '0'
        },
        content: [
            'set',
            'animate'
        ]
    },
    feImage: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive',
            'xlink'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'preserveAspectRatio',
            'href',
            'xlink:href'
        ],
        defaults: {
            preserveAspectRatio: 'xMidYMid meet'
        },
        content: [
            'animate',
            'animateTransform',
            'set'
        ]
    },
    feMerge: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive'
        ],
        attrs: [
            'class',
            'style'
        ],
        content: [
            'feMergeNode'
        ]
    },
    feMergeNode: {
        attrsGroups: [
            'core'
        ],
        attrs: [
            'in'
        ],
        content: [
            'animate',
            'set'
        ]
    },
    feMorphology: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive'
        ],
        attrs: [
            'class',
            'style',
            'in',
            'operator',
            'radius'
        ],
        defaults: {
            operator: 'erode',
            radius: '0'
        },
        content: [
            'animate',
            'set'
        ]
    },
    feOffset: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive'
        ],
        attrs: [
            'class',
            'style',
            'in',
            'dx',
            'dy'
        ],
        defaults: {
            dx: '0',
            dy: '0'
        },
        content: [
            'animate',
            'set'
        ]
    },
    fePointLight: {
        attrsGroups: [
            'core'
        ],
        attrs: [
            'x',
            'y',
            'z'
        ],
        defaults: {
            x: '0',
            y: '0',
            z: '0'
        },
        content: [
            'animate',
            'set'
        ]
    },
    feSpecularLighting: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive'
        ],
        attrs: [
            'class',
            'style',
            'in',
            'surfaceScale',
            'specularConstant',
            'specularExponent',
            'kernelUnitLength'
        ],
        defaults: {
            surfaceScale: '1',
            specularConstant: '1',
            specularExponent: '1'
        },
        contentGroups: [
            'descriptive',
            // TODO: exactly one 'light source element'
            'lightSource'
        ]
    },
    feSpotLight: {
        attrsGroups: [
            'core'
        ],
        attrs: [
            'x',
            'y',
            'z',
            'pointsAtX',
            'pointsAtY',
            'pointsAtZ',
            'specularExponent',
            'limitingConeAngle'
        ],
        defaults: {
            x: '0',
            y: '0',
            z: '0',
            pointsAtX: '0',
            pointsAtY: '0',
            pointsAtZ: '0',
            specularExponent: '1'
        },
        content: [
            'animate',
            'set'
        ]
    },
    feTile: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive'
        ],
        attrs: [
            'class',
            'style',
            'in'
        ],
        content: [
            'animate',
            'set'
        ]
    },
    feTurbulence: {
        attrsGroups: [
            'core',
            'presentation',
            'filterPrimitive'
        ],
        attrs: [
            'class',
            'style',
            'baseFrequency',
            'numOctaves',
            'seed',
            'stitchTiles',
            'type'
        ],
        defaults: {
            baseFrequency: '0',
            numOctaves: '1',
            seed: '0',
            stitchTiles: 'noStitch',
            type: 'turbulence'
        },
        content: [
            'animate',
            'set'
        ]
    },
    filter: {
        attrsGroups: [
            'core',
            'presentation',
            'xlink'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'x',
            'y',
            'width',
            'height',
            'filterRes',
            'filterUnits',
            'primitiveUnits',
            'href',
            'xlink:href'
        ],
        defaults: {
            primitiveUnits: 'userSpaceOnUse',
            x: '-10%',
            y: '-10%',
            width: '120%',
            height: '120%'
        },
        contentGroups: [
            'descriptive',
            'filterPrimitive'
        ],
        content: [
            'animate',
            'set'
        ]
    },
    font: {
        attrsGroups: [
            'core',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'horiz-origin-x',
            'horiz-origin-y',
            'horiz-adv-x',
            'vert-origin-x',
            'vert-origin-y',
            'vert-adv-y'
        ],
        defaults: {
            'horiz-origin-x': '0',
            'horiz-origin-y': '0'
        },
        contentGroups: [
            'descriptive'
        ],
        content: [
            'font-face',
            'glyph',
            'hkern',
            'missing-glyph',
            'vkern'
        ]
    },
    'font-face': {
        attrsGroups: [
            'core'
        ],
        attrs: [
            'font-family',
            'font-style',
            'font-variant',
            'font-weight',
            'font-stretch',
            'font-size',
            'unicode-range',
            'units-per-em',
            'panose-1',
            'stemv',
            'stemh',
            'slope',
            'cap-height',
            'x-height',
            'accent-height',
            'ascent',
            'descent',
            'widths',
            'bbox',
            'ideographic',
            'alphabetic',
            'mathematical',
            'hanging',
            'v-ideographic',
            'v-alphabetic',
            'v-mathematical',
            'v-hanging',
            'underline-position',
            'underline-thickness',
            'strikethrough-position',
            'strikethrough-thickness',
            'overline-position',
            'overline-thickness'
        ],
        defaults: {
            'font-style': 'all',
            'font-variant': 'normal',
            'font-weight': 'all',
            'font-stretch': 'normal',
            'unicode-range': 'U+0-10FFFF',
            'units-per-em': '1000',
            'panose-1': '0 0 0 0 0 0 0 0 0 0',
            'slope': '0'
        },
        contentGroups: [
            'descriptive'
        ],
        content: [
            // TODO: "at most one 'font-face-src' element"
            'font-face-src'
        ]
    },
    // TODO: empty content
    'font-face-format': {
        attrsGroups: [
            'core'
        ],
        attrs: [
            'string'
        ]
    },
    'font-face-name': {
        attrsGroups: [
            'core'
        ],
        attrs: [
            'name'
        ]
    },
    'font-face-src': {
        attrsGroups: [
            'core'
        ],
        content: [
            'font-face-name',
            'font-face-uri'
        ]
    },
    'font-face-uri': {
        attrsGroups: [
            'core',
            'xlink'
        ],
        attrs: [
            'href',
            'xlink:href'
        ],
        content: [
            'font-face-format'
        ]
    },
    foreignObject: {
        attrsGroups: [
            'core',
            'conditionalProcessing',
            'graphicalEvent',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'transform',
            'x',
            'y',
            'width',
            'height'
        ],
        defaults: {
            x: 0,
            y: 0
        }
    },
    g: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'transform'
        ],
        contentGroups: [
            'animation',
            'descriptive',
            'shape',
            'structural',
            'paintServer'
        ],
        content: [
            'a',
            'altGlyphDef',
            'clipPath',
            'color-profile',
            'cursor',
            'filter',
            'font',
            'font-face',
            'foreignObject',
            'image',
            'marker',
            'mask',
            'pattern',
            'script',
            'style',
            'switch',
            'text',
            'view'
        ]
    },
    glyph: {
        attrsGroups: [
            'core',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'd',
            'horiz-adv-x',
            'vert-origin-x',
            'vert-origin-y',
            'vert-adv-y',
            'unicode',
            'glyph-name',
            'orientation',
            'arabic-form',
            'lang'
        ],
        defaults: {
            'arabic-form': 'initial'
        },
        contentGroups: [
            'animation',
            'descriptive',
            'shape',
            'structural',
            'paintServer'
        ],
        content: [
            'a',
            'altGlyphDef',
            'clipPath',
            'color-profile',
            'cursor',
            'filter',
            'font',
            'font-face',
            'foreignObject',
            'image',
            'marker',
            'mask',
            'pattern',
            'script',
            'style',
            'switch',
            'text',
            'view'
        ],
    },
    glyphRef: {
        attrsGroups: [
            'core',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'd',
            'horiz-adv-x',
            'vert-origin-x',
            'vert-origin-y',
            'vert-adv-y'
        ],
        contentGroups: [
            'animation',
            'descriptive',
            'shape',
            'structural',
            'paintServer'
        ],
        content: [
            'a',
            'altGlyphDef',
            'clipPath',
            'color-profile',
            'cursor',
            'filter',
            'font',
            'font-face',
            'foreignObject',
            'image',
            'marker',
            'mask',
            'pattern',
            'script',
            'style',
            'switch',
            'text',
            'view'
        ]
    },
    hatch: {
        attrsGroups: [
            'core',
            'presentation',
            'xlink'
        ],
        attrs: [
            'class',
            'style',
            'x',
            'y',
            'pitch',
            'rotate',
            'hatchUnits',
            'hatchContentUnits',
            'transform'
        ],
        defaults: {
            hatchUnits: 'objectBoundingBox',
            hatchContentUnits: 'userSpaceOnUse',
            x: '0',
            y: '0',
            pitch: '0',
            rotate: '0'
        },
        contentGroups: [
            'animation',
            'descriptive'
        ],
        content: [
            'hatchPath'
        ]
    },
    hatchPath: {
        attrsGroups: [
            'core',
            'presentation',
            'xlink'
        ],
        attrs: [
            'class',
            'style',
            'd',
            'offset'
        ],
        defaults: {
            offset: '0'
        },
        contentGroups: [
            'animation',
            'descriptive'
        ]
    },
    hkern: {
        attrsGroups: [
            'core'
        ],
        attrs: [
            'u1',
            'g1',
            'u2',
            'g2',
            'k'
        ]
    },
    image: {
        attrsGroups: [
            'core',
            'conditionalProcessing',
            'graphicalEvent',
            'xlink',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'preserveAspectRatio',
            'transform',
            'x',
            'y',
            'width',
            'height',
            'href',
            'xlink:href'
        ],
        defaults: {
            x: '0',
            y: '0',
            preserveAspectRatio: 'xMidYMid meet'
        },
        contentGroups: [
            'animation',
            'descriptive'
        ]
    },
    line: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'transform',
            'x1',
            'y1',
            'x2',
            'y2'
        ],
        defaults: {
            x1: '0',
            y1: '0',
            x2: '0',
            y2: '0'
        },
        contentGroups: [
            'animation',
            'descriptive'
        ]
    },
    linearGradient: {
        attrsGroups: [
            'core',
            'presentation',
            'xlink'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'x1',
            'y1',
            'x2',
            'y2',
            'gradientUnits',
            'gradientTransform',
            'spreadMethod',
            'href',
            'xlink:href'
        ],
        defaults: {
            x1: '0',
            y1: '0',
            x2: '100%',
            y2: '0',
            spreadMethod: 'pad'
        },
        contentGroups: [
            'descriptive'
        ],
        content: [
            'animate',
            'animateTransform',
            'set',
            'stop'
        ]
    },
    marker: {
        attrsGroups: [
            'core',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'viewBox',
            'preserveAspectRatio',
            'refX',
            'refY',
            'markerUnits',
            'markerWidth',
            'markerHeight',
            'orient'
        ],
        defaults: {
            markerUnits: 'strokeWidth',
            refX: '0',
            refY: '0',
            markerWidth: '3',
            markerHeight: '3'
        },
        contentGroups: [
            'animation',
            'descriptive',
            'shape',
            'structural',
            'paintServer'
        ],
        content: [
            'a',
            'altGlyphDef',
            'clipPath',
            'color-profile',
            'cursor',
            'filter',
            'font',
            'font-face',
            'foreignObject',
            'image',
            'marker',
            'mask',
            'pattern',
            'script',
            'style',
            'switch',
            'text',
            'view'
        ]
    },
    mask: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'x',
            'y',
            'width',
            'height',
            'maskUnits',
            'maskContentUnits'
        ],
        defaults: {
            maskUnits: 'objectBoundingBox',
            maskContentUnits: 'userSpaceOnUse',
            x: '-10%',
            y: '-10%',
            width: '120%',
            height: '120%'
        },
        contentGroups: [
            'animation',
            'descriptive',
            'shape',
            'structural',
            'paintServer'
        ],
        content: [
            'a',
            'altGlyphDef',
            'clipPath',
            'color-profile',
            'cursor',
            'filter',
            'font',
            'font-face',
            'foreignObject',
            'image',
            'marker',
            'mask',
            'pattern',
            'script',
            'style',
            'switch',
            'text',
            'view'
        ]
    },
    metadata: {
        attrsGroups: [
            'core'
        ]
    },
    'missing-glyph': {
        attrsGroups: [
            'core',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'd',
            'horiz-adv-x',
            'vert-origin-x',
            'vert-origin-y',
            'vert-adv-y'
        ],
        contentGroups: [
            'animation',
            'descriptive',
            'shape',
            'structural',
            'paintServer'
        ],
        content: [
            'a',
            'altGlyphDef',
            'clipPath',
            'color-profile',
            'cursor',
            'filter',
            'font',
            'font-face',
            'foreignObject',
            'image',
            'marker',
            'mask',
            'pattern',
            'script',
            'style',
            'switch',
            'text',
            'view'
        ]
    },
    mpath: {
        attrsGroups: [
            'core',
            'xlink'
        ],
        attrs: [
            'externalResourcesRequired',
            'href',
            'xlink:href'
        ],
        contentGroups: [
            'descriptive'
        ]
    },
    path: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'transform',
            'd',
            'pathLength'
        ],
        contentGroups: [
            'animation',
            'descriptive'
        ]
    },
    pattern: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'presentation',
            'xlink'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'viewBox',
            'preserveAspectRatio',
            'x',
            'y',
            'width',
            'height',
            'patternUnits',
            'patternContentUnits',
            'patternTransform',
            'href',
            'xlink:href'
        ],
        defaults: {
            patternUnits: 'objectBoundingBox',
            patternContentUnits: 'userSpaceOnUse',
            x: '0',
            y: '0',
            width: '0',
            height: '0',
            preserveAspectRatio: 'xMidYMid meet'
        },
        contentGroups: [
            'animation',
            'descriptive',
            'paintServer',
            'shape',
            'structural'
        ],
        content: [
            'a',
            'altGlyphDef',
            'clipPath',
            'color-profile',
            'cursor',
            'filter',
            'font',
            'font-face',
            'foreignObject',
            'image',
            'marker',
            'mask',
            'pattern',
            'script',
            'style',
            'switch',
            'text',
            'view'
        ]
    },
    polygon: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'transform',
            'points'
        ],
        contentGroups: [
            'animation',
            'descriptive'
        ]
    },
    polyline: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'transform',
            'points'
        ],
        contentGroups: [
            'animation',
            'descriptive'
        ]
    },
    radialGradient: {
        attrsGroups: [
            'core',
            'presentation',
            'xlink'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'cx',
            'cy',
            'r',
            'fx',
            'fy',
            'fr',
            'gradientUnits',
            'gradientTransform',
            'spreadMethod',
            'href',
            'xlink:href'
        ],
        defaults: {
            gradientUnits: 'objectBoundingBox',
            cx: '50%',
            cy: '50%',
            r: '50%'
        },
        contentGroups: [
            'descriptive'
        ],
        content: [
            'animate',
            'animateTransform',
            'set',
            'stop'
        ]
    },
    meshGradient: {
        attrsGroups: [
            'core',
            'presentation',
            'xlink'
        ],
        attrs: [
            'class',
            'style',
            'x',
            'y',
            'gradientUnits',
            'transform'
        ],
        contentGroups: [
            'descriptive',
            'paintServer',
            'animation',
        ],
        content: [
            'meshRow'
        ]
    },
    meshRow: {
        attrsGroups: [
            'core',
            'presentation'
        ],
        attrs: [
            'class',
            'style'
        ],
        contentGroups: [
            'descriptive'
        ],
        content: [
            'meshPatch'
        ]
    },
    meshPatch: {
        attrsGroups: [
            'core',
            'presentation'
        ],
        attrs: [
            'class',
            'style'
        ],
        contentGroups: [
            'descriptive'
        ],
        content: [
            'stop'
        ]
    },
    rect: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'transform',
            'x',
            'y',
            'width',
            'height',
            'rx',
            'ry'
        ],
        defaults: {
            x: '0',
            y: '0'
        },
        contentGroups: [
            'animation',
            'descriptive'
        ]
    },
    script: {
        attrsGroups: [
            'core',
            'xlink'
        ],
        attrs: [
            'externalResourcesRequired',
            'type',
            'href',
            'xlink:href'
        ]
    },
    set: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'animation',
            'xlink',
            'animationAttributeTarget',
            'animationTiming',
        ],
        attrs: [
            'externalResourcesRequired',
            'to'
        ],
        contentGroups: [
            'descriptive'
        ]
    },
    solidColor: {
        attrsGroups: [
            'core',
            'presentation'
        ],
        attrs: [
            'class',
            'style'
        ],
        contentGroups: [
            'paintServer'
        ]
    },
    stop: {
        attrsGroups: [
            'core',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'offset',
            'path'
        ],
        content: [
            'animate',
            'animateColor',
            'set'
        ]
    },
    style: {
        attrsGroups: [
            'core'
        ],
        attrs: [
            'type',
            'media',
            'title'
        ],
        defaults: {
            type: 'text/css'
        }
    },
    svg: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'documentEvent',
            'graphicalEvent',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'x',
            'y',
            'width',
            'height',
            'viewBox',
            'preserveAspectRatio',
            'zoomAndPan',
            'version',
            'baseProfile',
            'contentScriptType',
            'contentStyleType'
        ],
        defaults: {
            x: '0',
            y: '0',
            width: '100%',
            height: '100%',
            preserveAspectRatio: 'xMidYMid meet',
            zoomAndPan: 'magnify',
            version: '1.1',
            baseProfile: 'none',
            contentScriptType: 'application/ecmascript',
            contentStyleType: 'text/css'
        },
        contentGroups: [
            'animation',
            'descriptive',
            'shape',
            'structural',
            'paintServer'
        ],
        content: [
            'a',
            'altGlyphDef',
            'clipPath',
            'color-profile',
            'cursor',
            'filter',
            'font',
            'font-face',
            'foreignObject',
            'image',
            'marker',
            'mask',
            'pattern',
            'script',
            'style',
            'switch',
            'text',
            'view'
        ]
    },
    switch: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'transform'
        ],
        contentGroups: [
            'animation',
            'descriptive',
            'shape'
        ],
        content: [
            'a',
            'foreignObject',
            'g',
            'image',
            'svg',
            'switch',
            'text',
            'use'
        ]
    },
    symbol: {
        attrsGroups: [
            'core',
            'graphicalEvent',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'preserveAspectRatio',
            'viewBox',
            'refX',
            'refY'
        ],
        defaults: {
            refX: 0,
            refY: 0
        },
        contentGroups: [
            'animation',
            'descriptive',
            'shape',
            'structural',
            'paintServer'
        ],
        content: [
            'a',
            'altGlyphDef',
            'clipPath',
            'color-profile',
            'cursor',
            'filter',
            'font',
            'font-face',
            'foreignObject',
            'image',
            'marker',
            'mask',
            'pattern',
            'script',
            'style',
            'switch',
            'text',
            'view'
        ]
    },
    text: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'transform',
            'lengthAdjust',
            'x',
            'y',
            'dx',
            'dy',
            'rotate',
            'textLength'
        ],
        defaults: {
            x: '0',
            y: '0',
            lengthAdjust: 'spacing'
        },
        contentGroups: [
            'animation',
            'descriptive',
            'textContentChild'
        ],
        content: [
            'a'
        ]
    },
    textPath: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation',
            'xlink'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'href',
            'xlink:href',
            'startOffset',
            'method',
            'spacing',
            'd'
        ],
        defaults: {
            startOffset: '0',
            method: 'align',
            spacing: 'exact'
        },
        contentGroups: [
            'descriptive'
        ],
        content: [
            'a',
            'altGlyph',
            'animate',
            'animateColor',
            'set',
            'tref',
            'tspan'
        ]
    },
    title: {
        attrsGroups: [
            'core'
        ],
        attrs: [
            'class',
            'style'
        ]
    },
    tref: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation',
            'xlink'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'href',
            'xlink:href'
        ],
        contentGroups: [
            'descriptive'
        ],
        content: [
            'animate',
            'animateColor',
            'set'
        ]
    },
    tspan: {
        attrsGroups: [
            'conditionalProcessing',
            'core',
            'graphicalEvent',
            'presentation'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'x',
            'y',
            'dx',
            'dy',
            'rotate',
            'textLength',
            'lengthAdjust'
        ],
        contentGroups: [
            'descriptive'
        ],
        content: [
            'a',
            'altGlyph',
            'animate',
            'animateColor',
            'set',
            'tref',
            'tspan'
        ]
    },
    use: {
        attrsGroups: [
            'core',
            'conditionalProcessing',
            'graphicalEvent',
            'presentation',
            'xlink'
        ],
        attrs: [
            'class',
            'style',
            'externalResourcesRequired',
            'transform',
            'x',
            'y',
            'width',
            'height',
            'href',
            'xlink:href'
        ],
        defaults: {
            x: '0',
            y: '0'
        },
        contentGroups: [
            'animation',
            'descriptive'
        ]
    },
    view: {
        attrsGroups: [
            'core'
        ],
        attrs: [
            'externalResourcesRequired',
            'viewBox',
            'preserveAspectRatio',
            'zoomAndPan',
            'viewTarget'
        ],
        contentGroups: [
            'descriptive'
        ]
    },
    vkern: {
        attrsGroups: [
            'core'
        ],
        attrs: [
            'u1',
            'g1',
            'u2',
            'g2',
            'k'
        ]
    }
};

// http://wiki.inkscape.org/wiki/index.php/Inkscape-specific_XML_attributes
exports.editorNamespaces = [
    'http://sodipodi.sourceforge.net/DTD/sodipodi-0.dtd',
    'http://inkscape.sourceforge.net/DTD/sodipodi-0.dtd',
    'http://www.inkscape.org/namespaces/inkscape',
    'http://www.bohemiancoding.com/sketch/ns',
    'http://ns.adobe.com/AdobeIllustrator/10.0/',
    'http://ns.adobe.com/Graphs/1.0/',
    'http://ns.adobe.com/AdobeSVGViewerExtensions/3.0/',
    'http://ns.adobe.com/Variables/1.0/',
    'http://ns.adobe.com/SaveForWeb/1.0/',
    'http://ns.adobe.com/Extensibility/1.0/',
    'http://ns.adobe.com/Flows/1.0/',
    'http://ns.adobe.com/ImageReplacement/1.0/',
    'http://ns.adobe.com/GenericCustomNamespace/1.0/',
    'http://ns.adobe.com/XPath/1.0/',
    'http://schemas.microsoft.com/visio/2003/SVGExtensions/',
    'http://taptrix.com/vectorillustrator/svg_extensions',
    'http://www.figma.com/figma/ns',
    'http://purl.org/dc/elements/1.1/',
    'http://creativecommons.org/ns#',
    'http://www.w3.org/1999/02/22-rdf-syntax-ns#',
    'http://www.serif.com/',
    'http://www.vector.evaxdesign.sk'
];

// http://www.w3.org/TR/SVG11/linking.html#processingIRI
exports.referencesProps = [
    'clip-path',
    'color-profile',
    'fill',
    'filter',
    'marker-start',
    'marker-mid',
    'marker-end',
    'mask',
    'stroke',
    'style'
];

// http://www.w3.org/TR/SVG11/propidx.html
exports.inheritableAttrs = [
    'clip-rule',
    'color',
    'color-interpolation',
    'color-interpolation-filters',
    'color-profile',
    'color-rendering',
    'cursor',
    'direction',
    'dominant-baseline',
    'fill',
    'fill-opacity',
    'fill-rule',
    'font',
    'font-family',
    'font-size',
    'font-size-adjust',
    'font-stretch',
    'font-style',
    'font-variant',
    'font-weight',
    'glyph-orientation-horizontal',
    'glyph-orientation-vertical',
    'image-rendering',
    'letter-spacing',
    'marker',
    'marker-end',
    'marker-mid',
    'marker-start',
    'paint-order',
    'pointer-events',
    'shape-rendering',
    'stroke',
    'stroke-dasharray',
    'stroke-dashoffset',
    'stroke-linecap',
    'stroke-linejoin',
    'stroke-miterlimit',
    'stroke-opacity',
    'stroke-width',
    'text-anchor',
    'text-rendering',
    'transform',
    'visibility',
    'word-spacing',
    'writing-mode'
];

exports.presentationNonInheritableGroupAttrs = [
    'display',
    'clip-path',
    'filter',
    'mask',
    'opacity',
    'text-decoration',
    'transform',
    'unicode-bidi',
    'visibility'
];

// http://www.w3.org/TR/SVG11/single-page.html#types-ColorKeywords
exports.colorsNames = {
    'aliceblue': '#f0f8ff',
    'antiquewhite': '#faebd7',
    'aqua': '#0ff',
    'aquamarine': '#7fffd4',
    'azure': '#f0ffff',
    'beige': '#f5f5dc',
    'bisque': '#ffe4c4',
    'black': '#000',
    'blanchedalmond': '#ffebcd',
    'blue': '#00f',
    'blueviolet': '#8a2be2',
    'brown': '#a52a2a',
    'burlywood': '#deb887',
    'cadetblue': '#5f9ea0',
    'chartreuse': '#7fff00',
    'chocolate': '#d2691e',
    'coral': '#ff7f50',
    'cornflowerblue': '#6495ed',
    'cornsilk': '#fff8dc',
    'crimson': '#dc143c',
    'cyan': '#0ff',
    'darkblue': '#00008b',
    'darkcyan': '#008b8b',
    'darkgoldenrod': '#b8860b',
    'darkgray': '#a9a9a9',
    'darkgreen': '#006400',
    'darkgrey': '#a9a9a9',
    'darkkhaki': '#bdb76b',
    'darkmagenta': '#8b008b',
    'darkolivegreen': '#556b2f',
    'darkorange': '#ff8c00',
    'darkorchid': '#9932cc',
    'darkred': '#8b0000',
    'darksalmon': '#e9967a',
    'darkseagreen': '#8fbc8f',
    'darkslateblue': '#483d8b',
    'darkslategray': '#2f4f4f',
    'darkslategrey': '#2f4f4f',
    'darkturquoise': '#00ced1',
    'darkviolet': '#9400d3',
    'deeppink': '#ff1493',
    'deepskyblue': '#00bfff',
    'dimgray': '#696969',
    'dimgrey': '#696969',
    'dodgerblue': '#1e90ff',
    'firebrick': '#b22222',
    'floralwhite': '#fffaf0',
    'forestgreen': '#228b22',
    'fuchsia': '#f0f',
    'gainsboro': '#dcdcdc',
    'ghostwhite': '#f8f8ff',
    'gold': '#ffd700',
    'goldenrod': '#daa520',
    'gray': '#808080',
    'green': '#008000',
    'greenyellow': '#adff2f',
    'grey': '#808080',
    'honeydew': '#f0fff0',
    'hotpink': '#ff69b4',
    'indianred': '#cd5c5c',
    'indigo': '#4b0082',
    'ivory': '#fffff0',
    'khaki': '#f0e68c',
    'lavender': '#e6e6fa',
    'lavenderblush': '#fff0f5',
    'lawngreen': '#7cfc00',
    'lemonchiffon': '#fffacd',
    'lightblue': '#add8e6',
    'lightcoral': '#f08080',
    'lightcyan': '#e0ffff',
    'lightgoldenrodyellow': '#fafad2',
    'lightgray': '#d3d3d3',
    'lightgreen': '#90ee90',
    'lightgrey': '#d3d3d3',
    'lightpink': '#ffb6c1',
    'lightsalmon': '#ffa07a',
    'lightseagreen': '#20b2aa',
    'lightskyblue': '#87cefa',
    'lightslategray': '#789',
    'lightslategrey': '#789',
    'lightsteelblue': '#b0c4de',
    'lightyellow': '#ffffe0',
    'lime': '#0f0',
    'limegreen': '#32cd32',
    'linen': '#faf0e6',
    'magenta': '#f0f',
    'maroon': '#800000',
    'mediumaquamarine': '#66cdaa',
    'mediumblue': '#0000cd',
    'mediumorchid': '#ba55d3',
    'mediumpurple': '#9370db',
    'mediumseagreen': '#3cb371',
    'mediumslateblue': '#7b68ee',
    'mediumspringgreen': '#00fa9a',
    'mediumturquoise': '#48d1cc',
    'mediumvioletred': '#c71585',
    'midnightblue': '#191970',
    'mintcream': '#f5fffa',
    'mistyrose': '#ffe4e1',
    'moccasin': '#ffe4b5',
    'navajowhite': '#ffdead',
    'navy': '#000080',
    'oldlace': '#fdf5e6',
    'olive': '#808000',
    'olivedrab': '#6b8e23',
    'orange': '#ffa500',
    'orangered': '#ff4500',
    'orchid': '#da70d6',
    'palegoldenrod': '#eee8aa',
    'palegreen': '#98fb98',
    'paleturquoise': '#afeeee',
    'palevioletred': '#db7093',
    'papayawhip': '#ffefd5',
    'peachpuff': '#ffdab9',
    'peru': '#cd853f',
    'pink': '#ffc0cb',
    'plum': '#dda0dd',
    'powderblue': '#b0e0e6',
    'purple': '#800080',
    'rebeccapurple': '#639',
    'red': '#f00',
    'rosybrown': '#bc8f8f',
    'royalblue': '#4169e1',
    'saddlebrown': '#8b4513',
    'salmon': '#fa8072',
    'sandybrown': '#f4a460',
    'seagreen': '#2e8b57',
    'seashell': '#fff5ee',
    'sienna': '#a0522d',
    'silver': '#c0c0c0',
    'skyblue': '#87ceeb',
    'slateblue': '#6a5acd',
    'slategray': '#708090',
    'slategrey': '#708090',
    'snow': '#fffafa',
    'springgreen': '#00ff7f',
    'steelblue': '#4682b4',
    'tan': '#d2b48c',
    'teal': '#008080',
    'thistle': '#d8bfd8',
    'tomato': '#ff6347',
    'turquoise': '#40e0d0',
    'violet': '#ee82ee',
    'wheat': '#f5deb3',
    'white': '#fff',
    'whitesmoke': '#f5f5f5',
    'yellow': '#ff0',
    'yellowgreen': '#9acd32'
};

exports.colorsShortNames = {
  '#f0ffff': 'azure',
  '#f5f5dc': 'beige',
  '#ffe4c4': 'bisque',
  '#a52a2a': 'brown',
  '#ff7f50': 'coral',
  '#ffd700': 'gold',
  '#808080': 'gray',
  '#008000': 'green',
  '#4b0082': 'indigo',
  '#fffff0': 'ivory',
  '#f0e68c': 'khaki',
  '#faf0e6': 'linen',
  '#800000': 'maroon',
  '#000080': 'navy',
  '#808000': 'olive',
  '#ffa500': 'orange',
  '#da70d6': 'orchid',
  '#cd853f': 'peru',
  '#ffc0cb': 'pink',
  '#dda0dd': 'plum',
  '#800080': 'purple',
  '#f00': 'red',
  '#ff0000': 'red',
  '#fa8072': 'salmon',
  '#a0522d': 'sienna',
  '#c0c0c0': 'silver',
  '#fffafa': 'snow',
  '#d2b48c': 'tan',
  '#008080': 'teal',
  '#ff6347': 'tomato',
  '#ee82ee': 'violet',
  '#f5deb3': 'wheat'
};

// http://www.w3.org/TR/SVG11/single-page.html#types-DataTypeColor
exports.colorsProps = [
    'color', 'fill', 'stroke', 'stop-color', 'flood-color', 'lighting-color'
];
