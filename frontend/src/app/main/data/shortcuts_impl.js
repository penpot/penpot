/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) KALEIDOS INC
 */
"use strict";

import Mousetrap from 'mousetrap'

if (Mousetrap.addKeycodes) {
  Mousetrap.addKeycodes({
    219: '219'
  });
}

const target = Mousetrap.prototype || Mousetrap;
target.stopCallback = function (e, element, combo) {
  // if the element has the data attribute "mousetrap-dont-stop" then no need
  // to stop. It should be used like <div data-mousetrap-dont-stop>...</div>
  // or :div {:data-mousetrap-dont-stop true}
  if ('mousetrapDontStop' in element.dataset) {
    return false
  }

  if ('composedPath' in e && typeof e.composedPath === 'function') {
    // For open shadow trees, update `element` so that the following check works.
    const initialEventTarget = e.composedPath()[0];
    if (initialEventTarget !== e.target) {
      element = initialEventTarget;
    }
  }

  // stop for input, select, textarea and button
  const shouldStop = element.tagName == "INPUT" ||
    element.tagName == "SELECT" ||
    element.tagName == "TEXTAREA" ||
    (element.tagName == "BUTTON" && combo.includes("tab")) ||
    (element.contentEditable && element.contentEditable == "true");
  return shouldStop;
}

export default Mousetrap;
