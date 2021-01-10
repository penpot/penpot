/* jshint quotmark: false */
'use strict';

exports.type = 'perItem';

exports.active = true;

exports.description = 'converts style to attributes';

exports.params = {
    keepImportant: false
};

var stylingProps = require('./_collections').attrsGroups.presentation,
    rEscape = '\\\\(?:[0-9a-f]{1,6}\\s?|\\r\\n|.)',                 // Like \" or \2051. Code points consume one space.
    rAttr = '\\s*(' + g('[^:;\\\\]', rEscape) + '*?)\\s*',          // attribute name like ‘fill’
    rSingleQuotes = "'(?:[^'\\n\\r\\\\]|" + rEscape + ")*?(?:'|$)", // string in single quotes: 'smth'
    rQuotes = '"(?:[^"\\n\\r\\\\]|' + rEscape + ')*?(?:"|$)',       // string in double quotes: "smth"
    rQuotedString = new RegExp('^' + g(rSingleQuotes, rQuotes) + '$'),

    // Parentheses, E.g.: url(data:image/png;base64,iVBO...).
    // ':' and ';' inside of it should be threated as is. (Just like in strings.)
    rParenthesis = '\\(' + g('[^\'"()\\\\]+', rEscape, rSingleQuotes, rQuotes) + '*?' + '\\)',

    // The value. It can have strings and parentheses (see above). Fallbacks to anything in case of unexpected input.
    rValue = '\\s*(' + g('[^!\'"();\\\\]+?', rEscape, rSingleQuotes, rQuotes, rParenthesis, '[^;]*?') + '*?' + ')',

    // End of declaration. Spaces outside of capturing groups help to do natural trimming.
    rDeclEnd = '\\s*(?:;\\s*|$)',

    // Important rule
    rImportant = '(\\s*!important(?![-(\w]))?',

    // Final RegExp to parse CSS declarations.
    regDeclarationBlock = new RegExp(rAttr + ':' + rValue + rImportant + rDeclEnd, 'ig'),

    // Comments expression. Honors escape sequences and strings.
    regStripComments = new RegExp(g(rEscape, rSingleQuotes, rQuotes, '/\\*[^]*?\\*/'), 'ig');

/**
 * Convert style in attributes. Cleanups comments and illegal declarations (without colon) as a side effect.
 *
 * @example
 * <g style="fill:#000; color: #fff;">
 *             ⬇
 * <g fill="#000" color="#fff">
 *
 * @example
 * <g style="fill:#000; color: #fff; -webkit-blah: blah">
 *             ⬇
 * <g fill="#000" color="#fff" style="-webkit-blah: blah">
 *
 * @param {Object} item current iteration item
 * @return {Boolean} if false, item will be filtered out
 *
 * @author Kir Belevich
 */
exports.fn = function(item, params) {
    /* jshint boss: true */

    if (item.elem && item.hasAttr('style')) {
            // ['opacity: 1', 'color: #000']
        var styleValue = item.attr('style').value,
            styles = [],
            attrs = {};

        // Strip CSS comments preserving escape sequences and strings.
        styleValue = styleValue.replace(regStripComments, function(match) {
            return match[0] == '/' ? '' :
                match[0] == '\\' && /[-g-z]/i.test(match[1]) ? match[1] : match;
        });

        regDeclarationBlock.lastIndex = 0;
        for (var rule; rule = regDeclarationBlock.exec(styleValue);) {
            if (!params.keepImportant || !rule[3]) {
                styles.push([rule[1], rule[2]]);
            }
        }

        if (styles.length) {

            styles = styles.filter(function(style) {
                if (style[0]) {
                    var prop = style[0].toLowerCase(),
                        val = style[1];

                    if (rQuotedString.test(val)) {
                        val = val.slice(1, -1);
                    }

                    if (stylingProps.indexOf(prop) > -1) {

                        attrs[prop] = {
                            name: prop,
                            value: val,
                            local: prop,
                            prefix: ''
                        };

                        return false;
                    }
                }

                return true;
            });

            Object.assign(item.attrs, attrs);

            if (styles.length) {
                item.attr('style').value = styles
                    .map(function(declaration) { return declaration.join(':') })
                    .join(';');
            } else {
                item.removeAttr('style');
            }

        }

    }

};

function g() {
    return '(?:' + Array.prototype.join.call(arguments, '|') + ')';
}
