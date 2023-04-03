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
target.stopCallback = function(e, element, combo) {
  // if the element has the class "mousetrap" then no need to stop
  if ((' ' + element.className + ' ').indexOf(' mousetrap ') > -1) {
      return false;
  }

  // stop for input, select, textarea and button
  return element.tagName == 'INPUT' || 
         element.tagName == 'SELECT' || 
         element.tagName == 'TEXTAREA' || 
         (element.tagName == 'BUTTON' && combo.includes("tab")) ||
         (element.contentEditable && element.contentEditable == 'true');
}

export default Mousetrap;
