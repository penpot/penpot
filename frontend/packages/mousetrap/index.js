/**
 * Copyright 2012-2017 Craig Campbell
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Mousetrap is a simple keyboard shortcut library for Javascript with
 * no external dependencies
 *
 * @version 1.6.5
 * @url craig.is/killing/mice
 */
const globalDocument = globalThis?.document;
/**
 * Canonical key names, all lowercase.
 *
 * `event.key` (final character for the active layout) is the source of
 * truth. Single printable characters are lowercased, `" "` becomes
 * `"space"`, and named keys map through this table.modifiers pressed
 * alone (`Shift`, `Control`, ...) normalize to `shift`, `ctrl`, `alt`,
 * `meta` so bind-time and event-time tokens always match.
 *
 * @type {Object}
 */
var _KEY_NAMES = {
  " ": "space",
  Spacebar: "space",
  ArrowUp: "up",
  Up: "up",
  ArrowDown: "down",
  Down: "down",
  ArrowLeft: "left",
  Left: "left",
  ArrowRight: "right",
  Right: "right",
  Escape: "escape",
  Esc: "escape",
  Enter: "enter",
  Backspace: "backspace",
  Delete: "del",
  Del: "del",
  Insert: "ins",
  Ins: "ins",
  Tab: "tab",
  Home: "home",
  End: "end",
  PageUp: "pageup",
  PageDown: "pagedown",
  CapsLock: "capslock",
  Shift: "shift",
  Control: "ctrl",
  Alt: "alt",
  Meta: "meta",
};

/**
 * Aliases accepted in bind strings, all resolving to lowercase canonical
 * tokens. `mod` follows the platform (Meta on Apple, Ctrl elsewhere).
 *
 * @type {Object}
 */
var globalNavigator = globalThis.navigator;

var _SPECIAL_ALIASES = {
  ctrl: "ctrl",
  control: "ctrl",
  alt: "alt",
  option: "alt",
  shift: "shift",
  meta: "meta",
  command: "meta",
  mod: /Mac|iPod|iPhone|iPad/.test(globalNavigator?.platform) ? "meta" : "ctrl",
  return: "enter",
  enter: "enter",
  esc: "escape",
  escape: "escape",
  del: "del",
  delete: "del",
  ins: "ins",
  insert: "ins",
  space: "space",
  plus: "+",
};

/**
 * cross browser add event method
 *
 * @param {Element|HTMLDocument} object
 * @param {string} type
 * @param {Function} callback
 * @returns void
 */
function _addEvent(object, type, callback) {
  object.addEventListener(type, callback, false);
}

/**
 * takes the event and returns the canonical key
 *
 * `event.key` is the source of truth: single characters lowercase so
 * `Shift+Z` matches a `ctrl+shift+z` bind, `" "` maps to `space`, and
 * named keys map through `_KEY_NAMES`. Numpad digits stay positional
 * (`num0`..`num9`) so they keep their own bindings.
 *
 * @param {Event} e
 * @return {string|null}
 */
function _characterFromEvent(e) {
  // Numpad digits as "num0".."num9" — keeps them separate from main-row bindings across NumLock states and event types.
  if (e.code && e.code.indexOf("Numpad") === 0) {
    var suffix = e.code.substring(6);
    if (suffix.length === 1 && suffix >= "0" && suffix <= "9") {
      return "num" + suffix;
    }
  }

  var key = e.key;
  if (typeof key !== "string" || key === "") {
    return null;
  }

  if (_KEY_NAMES[key]) {
    return _KEY_NAMES[key];
  }

  if (key.length === 1) {
    return key.toLowerCase();
  }

  if (/^F\d{1,2}$/.test(key)) {
    return key.toLowerCase();
  }

  if (key === "Dead") {
    return null;
  }

  return key.toLowerCase();
}

/**
 * checks if two arrays are equal
 *
 * @param {Array} modifiers1
 * @param {Array} modifiers2
 * @returns {boolean}
 */
function _modifiersMatch(modifiers1, modifiers2) {
  return modifiers1.sort().join(",") === modifiers2.sort().join(",");
}

/**
 * takes a key event and figures out what the modifiers are
 *
 * On Windows `AltGr` reports `ctrlKey + altKey` as well. When the
 * `AltGraph` modifier is active the phantom `ctrl` is dropped so typing
 * (e.g. `]` on Spanish keyboards) does not fire `ctrl+alt` shortcuts.
 *
 * @param {Event} e
 * @returns {Array}
 */
function _eventModifiers(e) {
  var modifiers = [];
  var altGraph =
    typeof e.getModifierState === "function" &&
    e.getModifierState("AltGraph");

  if (e.shiftKey) {
    modifiers.push("shift");
  }

  if (e.altKey) {
    modifiers.push("alt");
  }

  if (e.ctrlKey && !altGraph) {
    modifiers.push("ctrl");
  }

  if (e.metaKey) {
    modifiers.push("meta");
  }

  return modifiers;
}

/**
 * prevents default for this event
 *
 * @param {Event} e
 * @returns void
 */
function _preventDefault(e) {
  e.preventDefault();
}

/**
 * stops propogation for this event
 *
 * @param {Event} e
 * @returns void
 */
function _stopPropagation(e) {
  e.stopPropagation();
}

/**
 * determines if the key is a modifier key or not
 *
 * @param {string} key canonical lowercase key
 * @returns {boolean}
 */
function _isModifier(key) {
  return key == "shift" || key == "ctrl" || key == "alt" || key == "meta";
}

/**
 * Converts from a string key combination to an array
 *
 * @param  {string} combination like "command+shift+l"
 * @return {Array}
 */
function _keysFromString(combination) {
  if (combination === "+") {
    return ["+"];
  }

  combination = combination.replace(/\+{2}/g, "+plus");
  return combination.split("+");
}

/**
 * Gets info for a specific key combination
 *
 * @param  {string} combination key combination ("command+s" or "a" or "*")
 * @param  {string=} action
 * @returns {Object}
 */
function _getKeyInfo(combination, action) {
  var keys;
  var key;
  var i;
  var modifiers = [];

  // take the keys from this pattern and figure out what the actual
  // pattern is all about
  keys = _keysFromString(combination);

  for (i = 0; i < keys.length; ++i) {
    key = keys[i];

    // normalize key names to lowercase canonical tokens
    if (_SPECIAL_ALIASES[key]) {
      key = _SPECIAL_ALIASES[key];
    } else if (key.length === 1) {
      key = key.toLowerCase();
    } else {
      key = (_KEY_NAMES[key] || key).toLowerCase();
    }

    // if this key is a modifier then add it to the list of modifiers
    if (_isModifier(key)) {
      modifiers.push(key);
    }
  }

  // everything runs on keydown unless the caller asked for keyup
  if (!action) {
    action = "keydown";
  }

  return {
    key: key,
    modifiers: modifiers,
    action: action,
  };
}

function _belongsTo(element, ancestor) {
  if (element === null || element === globalDocument) {
    return false;
  }

  if (element === ancestor) {
    return true;
  }

  return _belongsTo(element.parentNode, ancestor);
}

function Mousetrap(targetElement) {
  var self = this;

  targetElement = targetElement || globalDocument;

  /**
   * element to attach key events to
   *
   * @type {Element}
   */
  self.target = targetElement;

  /**
   * a list of all the callbacks setup via Mousetrap.bind()
   *
   * @type {Object}
   */
  self._callbacks = {};

  /**
   * direct map of string combinations to callbacks used for trigger()
   *
   * @type {Object}
   */
  self._directMap = {};

  /**
   * keeps track of what level each sequence is at since multiple
   * sequences can start out with the same sequence
   *
   * @type {Object}
   */
  var _sequenceLevels = {};

  /**
   * variable to store the setTimeout call
   *
   * @type {null|number}
   */
  var _resetTimer;

  /**
   * temporary state where we will ignore the next keyup
   *
   * @type {boolean|string}
   */
  var _ignoreNextKeyup = false;

  /**
   * are we currently inside of a sequence?
   * type of action ("keyup" or "keydown") or false
   *
   * @type {boolean|string}
   */
  var _nextExpectedAction = false;

  /**
   * resets all sequence counters except for the ones passed in
   *
   * @param {Object} doNotReset
   * @returns void
   */
  function _resetSequences(doNotReset) {
    doNotReset = doNotReset || {};

    var activeSequences = false,
      key;

    for (key in _sequenceLevels) {
      if (doNotReset[key]) {
        activeSequences = true;
        continue;
      }
      _sequenceLevels[key] = 0;
    }

    if (!activeSequences) {
      _nextExpectedAction = false;
    }
  }

  /**
   * finds all callbacks that match based on the key, modifiers,
   * and action
   *
   * @param {string} character canonical lowercase key
   * @param {Array} modifiers
   * @param {Event|Object} e
   * @param {string=} sequenceName - name of the sequence we are looking for
   * @param {string=} combination
   * @param {number=} level
   * @returns {Array}
   */
  function _getMatches(
    character,
    modifiers,
    e,
    sequenceName,
    combination,
    level,
  ) {
    var i;
    var callback;
    var matches = [];
    var action = e.type;

    // if there are no events related to this key
    if (!self._callbacks[character]) {
      return [];
    }

    // if a modifier key is coming up on its own we should allow it
    if (action == "keyup" && _isModifier(character)) {
      modifiers = [character];
    }

    // loop through all callbacks for the key that was pressed
    // and see if any of them match
    for (i = 0; i < self._callbacks[character].length; ++i) {
      callback = self._callbacks[character][i];

      // if a sequence name is not specified, but this is a sequence at
      // the wrong level then move onto the next match
      if (
        !sequenceName &&
        callback.seq &&
        _sequenceLevels[callback.seq] != callback.level
      ) {
        continue;
      }

      // if the action we are looking for doesn't match the action we got
      // then we should keep going
      if (action != callback.action) {
        continue;
      }

      if (_modifiersMatch(modifiers, callback.modifiers)) {
        // when you bind a combination or sequence a second time it
        // should overwrite the first one.  if a sequenceName or
        // combination is specified in this call it does just that
        //
        // @todo make deleting its own method?
        var deleteCombo = !sequenceName && callback.combo == combination;
        var deleteSequence =
          sequenceName &&
          callback.seq == sequenceName &&
          callback.level == level;
        if (deleteCombo || deleteSequence) {
          self._callbacks[character].splice(i, 1);
        }

        matches.push(callback);
      }
    }

    return matches;
  }

  /**
   * actually calls the callback function
   *
   * if your callback function returns false this will use the jquery
   * convention - prevent default and stop propogation on the event
   *
   * @param {Function} callback
   * @param {Event} e
   * @returns void
   */
  function _fireCallback(callback, e, combo, sequence) {
    // if this event should not happen stop here
    if (self.stopCallback(e, e.target || e.srcElement, combo, sequence)) {
      return;
    }

    if (callback(e, combo) === false) {
      _preventDefault(e);
      _stopPropagation(e);
    }
  }

  /**
   * handles a character key event
   *
   * @param {string} character
   * @param {Array} modifiers
   * @param {Event} e
   * @returns void
   */
  self._handleKey = function (character, modifiers, e) {
    var callbacks = _getMatches(character, modifiers, e);
    var i;
    var doNotReset = {};
    var maxLevel = 0;
    var processedSequenceCallback = false;

    // Calculate the maxLevel for sequences so we can only execute the longest callback sequence
    for (i = 0; i < callbacks.length; ++i) {
      if (callbacks[i].seq) {
        maxLevel = Math.max(maxLevel, callbacks[i].level);
      }
    }

    // loop through matching callbacks for this key event
    for (i = 0; i < callbacks.length; ++i) {
      // fire for all sequence callbacks
      // this is because if for example you have multiple sequences
      // bound such as "g i" and "g t" they both need to fire the
      // callback for matching g cause otherwise you can only ever
      // match the first one
      if (callbacks[i].seq) {
        // only fire callbacks for the maxLevel to prevent
        // subsequences from also firing
        //
        // for example 'a option b' should not cause 'option b' to fire
        // even though 'option b' is part of the other sequence
        //
        // any sequences that do not match here will be discarded
        // below by the _resetSequences call
        if (callbacks[i].level != maxLevel) {
          continue;
        }

        processedSequenceCallback = true;

        // keep a list of which sequences were matches for later
        doNotReset[callbacks[i].seq] = 1;
        _fireCallback(
          callbacks[i].callback,
          e,
          callbacks[i].combo,
          callbacks[i].seq,
        );
        continue;
      }

      // if there were no sequence matches but we are still here
      // that means this is a regular match so we should fire that
      if (!processedSequenceCallback) {
        _fireCallback(callbacks[i].callback, e, callbacks[i].combo);
      }
    }

    // if the key you pressed matches the type of sequence without
    // being a modifier (ie "keyup" or "keydown") then we should
    // reset all sequences that were not matched by this event
    //
    // this is so, for example, if you have the sequence "h a t" and you
    // type "h e a r t" it does not match.  in this case the "e" will
    // cause the sequence to reset
    //
    // modifier keys are ignored because you can have a sequence
    // that contains modifiers such as "enter ctrl+space" and in most
    // cases the modifier key will be pressed before the next key
    if (
      e.type == _nextExpectedAction &&
      !_isModifier(character)
    ) {
      _resetSequences(doNotReset);
    }
  };

  /**
   * handles a key event
   *
   * @param {Event} e
   * @returns void
   */
  function _handleKeyEvent(e) {
    var character = _characterFromEvent(e);
    // no character found then stop
    if (!character) {
      return;
    }

    // need to use === for the character check because the character can be 0
    if (e.type == "keyup" && _ignoreNextKeyup === character) {
      _ignoreNextKeyup = false;
      return;
    }

    self.handleKey(character, _eventModifiers(e), e);
  }

  /**
   * called to set a 1 second timeout on the specified sequence
   *
   * this is so after each key press in the sequence you have 1 second
   * to press the next key before you have to start over
   *
   * @returns void
   */
  function _resetSequenceTimer() {
    clearTimeout(_resetTimer);
    _resetTimer = setTimeout(_resetSequences, 1000);
  }

  /**
   * binds a key sequence to an event
   *
   * @param {string} combo - combo specified in bind call
   * @param {Array} keys
   * @param {Function} callback
   * @param {string=} action
   * @returns void
   */
  function _bindSequence(combo, keys, callback, action) {
    // start off by adding a sequence level record for this combination
    // and setting the level to 0
    _sequenceLevels[combo] = 0;

    /**
     * callback to increase the sequence level for this sequence and reset
     * all other sequences that were active
     *
     * @param {string} nextAction
     * @returns {Function}
     */
    function _increaseSequence(nextAction) {
      return function () {
        _nextExpectedAction = nextAction;
        ++_sequenceLevels[combo];
        _resetSequenceTimer();
      };
    }

    /**
     * wraps the specified callback inside of another function in order
     * to reset all sequence counters as soon as this sequence is done
     *
     * @param {Event} e
     * @returns void
     */
    function _callbackAndReset(e) {
      _fireCallback(callback, e, combo);

      // we should ignore the next key up if the action is keydown.
      // this is so if you finish a sequence and
      // release the key the final key will not trigger a keyup
      if (action !== "keyup") {
        _ignoreNextKeyup = _characterFromEvent(e);
      }

      // weird race condition if a sequence ends with the key
      // another sequence begins with
      setTimeout(_resetSequences, 10);
    }

    // loop through keys one at a time and bind the appropriate callback
    // function.  for any key leading up to the final one it should
    // increase the sequence. after the final, it should reset all sequences
    //
    // if an action is specified in the original bind call then that will
    // be used throughout.  otherwise we will pass the action that the
    // next key in the sequence should match.
    for (var i = 0; i < keys.length; ++i) {
      var isFinal = i + 1 === keys.length;
      var wrappedCallback = isFinal
        ? _callbackAndReset
        : _increaseSequence(action || _getKeyInfo(keys[i + 1]).action);
      _bindSingle(keys[i], wrappedCallback, action, combo, i);
    }
  }

  /**
   * binds a single keyboard combination
   *
   * @param {string} combination
   * @param {Function} callback
   * @param {string=} action
   * @param {string=} sequenceName - name of sequence if part of sequence
   * @param {number=} level - what part of the sequence the command is
   * @returns void
   */
  function _bindSingle(
    combination,
    callback,
    action,
    sequenceName,
    level,
    overwrite,
  ) {
    // store a direct mapped reference for use with Mousetrap.trigger
    self._directMap[combination + ":" + action] = callback;

    // make sure multiple spaces in a row become a single space
    combination = combination.replace(/\s+/g, " ");

    var sequence = combination.split(" ");
    var info;

    // if this pattern is a sequence of keys then run through this method
    // to reprocess each pattern one key at a time
    if (sequence.length > 1) {
      _bindSequence(combination, sequence, callback, action);
      return;
    }

    info = _getKeyInfo(combination, action);

    // make sure to initialize array if this is the first time
    // a callback is added for this key
    self._callbacks[info.key] = self._callbacks[info.key] || [];

    // remove an existing match if there is one
    if (overwrite) {
      _getMatches(
        info.key,
        info.modifiers,
        { type: info.action },
        sequenceName,
        combination,
        level,
      );
    }

    // add this call back to the array
    // if it is a sequence put it at the beginning
    // if not put it at the end
    //
    // this is important because the way these are processed expects
    // the sequence ones to come first
    self._callbacks[info.key][sequenceName ? "unshift" : "push"]({
      callback: callback,
      modifiers: info.modifiers,
      action: info.action,
      seq: sequenceName,
      level: level,
      combo: combination,
    });
  }

  /**
   * binds multiple combinations to the same callback
   *
   * @param {Array} combinations
   * @param {Function} callback
   * @param {string|undefined} action
   * @returns void
   */
  self._bindMultiple = function (combinations, callback, action, overwrite) {
    for (var i = 0; i < combinations.length; ++i) {
      _bindSingle(
        combinations[i],
        callback,
        action,
        undefined,
        undefined,
        overwrite,
      );
    }
  };

  if (targetElement) {
    _addEvent(targetElement, "keydown", _handleKeyEvent);
    _addEvent(targetElement, "keyup", _handleKeyEvent);
  }
}

/**
 * binds an event to mousetrap
 *
 * can be a single key, a combination of keys separated with +,
 * an array of keys, or a sequence of keys separated by spaces
 *
 * be sure to list the modifier keys first to make sure that the
 * correct key ends up getting bound (the last key in the pattern)
 *
 * @param {string|Array} keys
 * @param {Function} callback
 * @param {string=} action - 'keydown' or 'keyup' (defaults to 'keydown')
 * @returns void
 */
Mousetrap.prototype.bind = function (keys, callback, action, overwrite) {
  var self = this;
  keys = keys instanceof Array ? keys : [keys];
  self._bindMultiple.call(self, keys, callback, action, overwrite);
  return self;
};

/**
 * unbinds an event to mousetrap
 *
 * the unbinding sets the callback function of the specified key combo
 * to an empty function and deletes the corresponding key in the
 * _directMap dict.
 *
 * TODO: actually remove this from the _callbacks dictionary instead
 * of binding an empty function
 *
 * the keycombo+action has to be exactly the same as
 * it was defined in the bind method
 *
 * @param {string|Array} keys
 * @param {string} action
 * @returns void
 */
Mousetrap.prototype.unbind = function (keys, action) {
  var self = this;
  return self.bind.call(self, keys, function () {}, action);
};

/**
 * triggers an event that has already been bound
 *
 * @param {string} keys
 * @param {string=} action
 * @returns void
 */
Mousetrap.prototype.trigger = function (keys, action) {
  var self = this;
  if (self._directMap[keys + ":" + action]) {
    self._directMap[keys + ":" + action]({}, keys);
  }
  return self;
};

/**
 * resets the library back to its initial state.  this is useful
 * if you want to clear out the current keyboard shortcuts and bind
 * new ones - for example if you switch to another page
 *
 * @returns void
 */
Mousetrap.prototype.reset = function () {
  var self = this;
  self._callbacks = {};
  self._directMap = {};
  return self;
};

/**
 * should we stop this event before firing off callbacks
 *
 * @param {Event} e
 * @param {Element} element
 * @return {boolean}
 */
Mousetrap.prototype.stopCallback = function (e, element, combo) {
  // if the element has the data attribute "mousetrap-dont-stop" then no need
  // to stop. It should be used like <div data-mousetrap-dont-stop>...</div>
  // or :div {:data-mousetrap-dont-stop true}
  if ("mousetrapDontStop" in element.dataset) {
    return false;
  }

  if ((" " + element.className + " ").indexOf(" mousetrap ") > -1) {
    return false;
  }

  // Keyup events need to be dispatched always. Otherwise some events can be stuck
  if (e.type == "keyup") {
    return false;
  }

  if ("composedPath" in e && typeof e.composedPath === "function") {
    // For open shadow trees, update `element` so that the following check works.
    const initialEventTarget = e.composedPath()[0];
    if (initialEventTarget !== e.target) {
      element = initialEventTarget;
    }
  }

  // stop for input, select, textarea and button
  const shouldStop =
    element.tagName == "INPUT" ||
    element.tagName == "SELECT" ||
    element.tagName == "TEXTAREA" ||
    (element.tagName == "BUTTON" && typeof combo === "string" && combo.includes("tab")) ||
    (element.contentEditable &&
      (element.contentEditable == "true" ||
        element.contentEditable === "plaintext-only"));

  return shouldStop;
};

/**
 * exposes _handleKey publicly so it can be overwritten by extensions
 */
Mousetrap.prototype.handleKey = function () {
  var self = this;
  return self._handleKey.apply(self, arguments);
};

/**
 * Init the global mousetrap functions
 *
 * This method is needed to allow the global mousetrap functions to work
 * now that mousetrap is a constructor function.
 */
var instance = new Mousetrap(globalDocument);

export default instance;
