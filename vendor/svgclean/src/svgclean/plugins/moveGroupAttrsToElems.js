'use strict';

exports.type = 'perItem';

exports.active = true;

exports.description = 'moves some group attributes to the content elements';

var collections = require('./_collections.js'),
    pathElems = collections.pathElems.concat(['g', 'text']),
    referencesProps = collections.referencesProps,
    inheritableAttrs = collections.inheritableAttrs;

/**
 * Move group attrs to the content elements.
 *
 * @example
 * <g transform="scale(2)">
 *     <path transform="rotate(45)" d="M0,0 L10,20"/>
 *     <path transform="translate(10, 20)" d="M0,10 L20,30"/>
 * </g>
 *                          ⬇
 * <g>
 *     <path transform="scale(2) rotate(45)" d="M0,0 L10,20"/>
 *     <path transform="scale(2) translate(10, 20)" d="M0,10 L20,30"/>
 * </g>
 *
 * @param {Object} item current iteration item
 * @return {Boolean} if false, item will be filtered out
 *
 * @author Kir Belevich
 */
exports.fn = function(item) {

    if (item.isElem('g') && !item.isEmpty()) {

        inheritableAttrs.forEach(function(currentAttr) {

            if (item.hasAttr(currentAttr)) {
                var attr = item.attr(currentAttr);

                item.content.forEach(function(inner) {

                    if (currentAttr === 'transform' && inner.hasAttr(currentAttr)) {
                        // if attr is transform and the inner has transform we concatenate it
                        inner.attr(currentAttr).value = attr.value + ' ' + inner.attr(currentAttr).value;
                    } else if (!inner.hasAttr(currentAttr)){
                        // If the inner has the attr already we don't override it
                        inner.addAttr({
                            ...attr
                        });
                    }

                    
                });

                item.removeAttr(currentAttr);
            }
            
        });
        
    }
    
};
