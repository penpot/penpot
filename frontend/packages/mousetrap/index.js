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
const _KEY_NAMES = {
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
const globalNavigator = globalThis.navigator;

const _SPECIAL_ALIASES = {
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
 * Physical codes treated positionally. Letters and digits resolve by
 * glyph (`event.key`), but punctuation resolves by position (`event.code`)
 * through the layout map, so the same physical key matches on every
 * layout with and without `Shift`. Without a map the typed glyph applies.
 *
 * @type {Set<string>}
 */
const _PUNCTUATION_CODES = new Set([
  "Minus",
  "Equal",
  "BracketLeft",
  "BracketRight",
  "Backslash",
  "Semicolon",
  "Quote",
  "Backquote",
  "Comma",
  "Period",
  "Slash",
]);

/**
 * Resolves an event to its canonical key. Implemented as
 * `Mousetrap#_characterFromEvent` so punctuation can consult the
 * instance layout map (see `setLayoutMap`).
 */

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
  const modifiers = [];
  const altGraph =
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
  let keys;
  let key;
  const modifiers = [];

  // take the keys from this pattern and figure out what the actual
  // pattern is all about
  keys = _keysFromString(combination);

  for (let i = 0; i < keys.length; ++i) {
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

class Mousetrap {
  constructor(targetElement = globalDocument) {
    /**
     * a list of all the callbacks setup via Mousetrap.bind()
     *
     * @type {Object}
     */
    this._callbacks = {};

    /**
     * direct map of string combinations to callbacks used for trigger()
     *
     * @type {Object}
     */
    this._directMap = {};

    /**
     * keeps track of what level each sequence is at since multiple
     * sequences can start out with the same sequence
     *
     * @type {Object}
     */
    this._sequenceLevels = {};

    /**
     * variable to store the setTimeout call
     *
     * @type {null|number}
     */
    this._resetTimer = undefined;

    /**
     * temporary state where we will ignore the next keyup
     *
     * @type {boolean|string}
     */
    this._ignoreNextKeyup = false;

    /**
     * are we currently inside of a sequence?
     * type of action ("keyup" or "keydown") or false
     *
     * @type {boolean|string}
     */
    this._nextExpectedAction = false;

    /**
     * layout map for positional punctuation resolution, as returned by
     * `navigator.keyboard.getLayoutMap()` (an object with `.get(code)`).
     * `null` means glyph fallback. Set via `setLayoutMap`.
     *
     * @type {Object|null}
     */
    this._layoutMap = null;

    if (targetElement) {
      targetElement.addEventListener("keydown", (e) => this._handleKeyEvent(e));
      targetElement.addEventListener("keyup", (e) => this._handleKeyEvent(e));
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
  bind(keys, callback, action, overwrite) {
    keys = keys instanceof Array ? keys : [keys];
    this._bindMultiple(keys, callback, action, overwrite);
    return this;
  }

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
  unbind(keys, action) {
    return this.bind(keys, function () {}, action);
  }

  /**
   * triggers an event that has already been bound
   *
   * @param {string} keys
   * @param {string=} action
   * @returns void
   */
  trigger(keys, action) {
    if (this._directMap[keys + ":" + action]) {
      this._directMap[keys + ":" + action]({}, keys);
    }
    return this;
  }

  /**
   * resets the library back to its initial state.  this is useful
   * if you want to clear out the current keyboard shortcuts and bind
   * new ones - for example if you switch to another page
   *
   * @returns void
   */
  reset() {
    this._callbacks = {};
    this._directMap = {};
    return this;
  }

  /**
   * sets the keyboard layout map used for positional punctuation
   * resolution (the same map the shortcut recorder uses).
   *
   * Pass the `KeyboardLayoutMap` from
   * `navigator.keyboard.getLayoutMap()`, or `null` to go back to the
   * glyph fallback. Read live at dispatch time, so call it whenever
   * the map resolves without rebinding.
   *
   * @param {Object|null} map - object with `.get(code)`, or null
   * @returns void
   */
  setLayoutMap(map) {
    this._layoutMap = map;
    return this;
  }

  /**
   * takes the event and returns the canonical key
   *
   * Letters, digits and named keys resolve by glyph: single characters
   * lowercase so `Shift+Z` matches a `ctrl+shift+z` bind, `" "` maps to
   * `space`, and named keys map through `_KEY_NAMES`. The exception is a
   * digit row key held with `Shift`, which resolves to the digit itself
   * (`Digit0` types ")" on US layouts but "0" on AZERTY, so `shift+0`
   * must mean the physical digit key everywhere). Punctuation
   * resolves positionally via the layout map (see `setLayoutMap`),
   * falling back to the glyph when no map is set. Numpad digits stay
   * positional (`num0`..`num9`) so they keep their own bindings.
   *
   * @param {Event} e
   * @return {string|null}
   */
  _characterFromEvent(e) {
    // Numpad digits as "num0".."num9" — keeps them separate from main-row bindings across NumLock states and event types.
    if (e.code && e.code.indexOf("Numpad") === 0) {
      const suffix = e.code.substring(6);
      if (suffix.length === 1 && suffix >= "0" && suffix <= "9") {
        return "num" + suffix;
      }
    }

    // Punctuation is positional: with a layout map the primary
    // (unshifted) character of the physical key wins, so `Shift+*` on a
    // Spanish layout (code `BracketRight`) resolves to `+` like the
    // recorder stores. Without a map the typed glyph applies.
    if (e.code && _PUNCTUATION_CODES.has(e.code) && this._layoutMap) {
      let mapped = null;
      try {
        mapped = this._layoutMap.get(e.code);
      } catch {
        mapped = null;
      }
      if (typeof mapped === "string" && mapped !== "") {
        return mapped.toLowerCase();
      }
    }

    const key = e.key;
    if (typeof key !== "string" || key === "") {
      return null;
    }

    // Shifted digits resolve to the digit itself: with `Shift` held,
    // `Digit0` types ")" on US layouts but "0" on AZERTY, so the glyph
    // can't be the token. `shift+0` means the physical digit key
    // everywhere, like `shift+z` means `z` regardless of `Shift`.
    if (e.shiftKey && e.code && /^Digit[0-9]$/.test(e.code) && key.length === 1) {
      return e.code.charAt(5);
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
   * should we stop this event before firing off callbacks
   *
   * @param {Event} e
   * @param {Element} element
   * @return {boolean}
   */
  stopCallback(e, element, combo) {
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
      (element.tagName == "BUTTON" &&
        typeof combo === "string" &&
        combo.includes("tab")) ||
      (element.contentEditable &&
        (element.contentEditable == "true" ||
          element.contentEditable === "plaintext-only"));

    return shouldStop;
  }

  /**
   * handles a character key event
   *
   * exposed publicly so it can be overwritten by extensions
   *
   * @param {string} character
   * @param {Array} modifiers
   * @param {Event} e
   * @returns void
   */
  handleKey(character, modifiers, e) {
    const callbacks = this._getMatches(character, modifiers, e);
    const doNotReset = {};
    let maxLevel = 0;
    let processedSequenceCallback = false;

    // Calculate the maxLevel for sequences so we can only execute the longest callback sequence
    for (let i = 0; i < callbacks.length; ++i) {
      if (callbacks[i].seq) {
        maxLevel = Math.max(maxLevel, callbacks[i].level);
      }
    }

    // loop through matching callbacks for this key event
    for (let i = 0; i < callbacks.length; ++i) {
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
        this._fireCallback(
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
        this._fireCallback(callbacks[i].callback, e, callbacks[i].combo);
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
    if (e.type == this._nextExpectedAction && !_isModifier(character)) {
      this._resetSequences(doNotReset);
    }
  }

  /**
   * binds multiple combinations to the same callback
   *
   * @param {Array} combinations
   * @param {Function} callback
   * @param {string|undefined} action
   * @returns void
   */
  _bindMultiple(combinations, callback, action, overwrite) {
    for (let i = 0; i < combinations.length; ++i) {
      this._bindSingle(
        combinations[i],
        callback,
        action,
        undefined,
        undefined,
        overwrite,
      );
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
  _bindSingle(combination, callback, action, sequenceName, level, overwrite) {
    // store a direct mapped reference for use with Mousetrap.trigger
    this._directMap[combination + ":" + action] = callback;

    // make sure multiple spaces in a row become a single space
    combination = combination.replace(/\s+/g, " ");

    const sequence = combination.split(" ");

    // if this pattern is a sequence of keys then run through this method
    // to reprocess each pattern one key at a time
    if (sequence.length > 1) {
      this._bindSequence(combination, sequence, callback, action);
      return;
    }

    const info = _getKeyInfo(combination, action);

    // make sure to initialize array if this is the first time
    // a callback is added for this key
    this._callbacks[info.key] = this._callbacks[info.key] || [];

    // remove an existing match if there is one
    if (overwrite) {
      this._getMatches(
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
    this._callbacks[info.key][sequenceName ? "unshift" : "push"]({
      callback: callback,
      modifiers: info.modifiers,
      action: info.action,
      seq: sequenceName,
      level: level,
      combo: combination,
    });
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
  _bindSequence(combo, keys, callback, action) {
    // start off by adding a sequence level record for this combination
    // and setting the level to 0
    this._sequenceLevels[combo] = 0;

    /**
     * callback to increase the sequence level for this sequence and reset
     * all other sequences that were active
     *
     * @param {string} nextAction
     * @returns {Function}
     */
    const _increaseSequence = (nextAction) => () => {
      this._nextExpectedAction = nextAction;
      ++this._sequenceLevels[combo];
      this._resetSequenceTimer();
    };

    /**
     * wraps the specified callback inside of another function in order
     * to reset all sequence counters as soon as this sequence is done
     *
     * @param {Event} e
     * @returns void
     */
    const _callbackAndReset = (e) => {
      this._fireCallback(callback, e, combo);

      // we should ignore the next key up if the action is keydown.
      // this is so if you finish a sequence and
      // release the key the final key will not trigger a keyup
      if (action !== "keyup") {
        this._ignoreNextKeyup = this._characterFromEvent(e);
      }

      // weird race condition if a sequence ends with the key
      // another sequence begins with
      setTimeout(() => this._resetSequences(), 10);
    };

    // loop through keys one at a time and bind the appropriate callback
    // function.  for any key leading up to the final one it should
    // increase the sequence. after the final, it should reset all sequences
    //
    // if an action is specified in the original bind call then that will
    // be used throughout.  otherwise we will pass the action that the
    // next key in the sequence should match.
    for (let i = 0; i < keys.length; ++i) {
      const isFinal = i + 1 === keys.length;
      const wrappedCallback = isFinal
        ? _callbackAndReset
        : _increaseSequence(action || _getKeyInfo(keys[i + 1]).action);
      this._bindSingle(keys[i], wrappedCallback, action, combo, i);
    }
  }

  /**
   * resets all sequence counters except for the ones passed in
   *
   * @param {Object} doNotReset
   * @returns void
   */
  _resetSequences(doNotReset) {
    doNotReset = doNotReset || {};

    let activeSequences = false;

    for (const key in this._sequenceLevels) {
      if (doNotReset[key]) {
        activeSequences = true;
        continue;
      }
      this._sequenceLevels[key] = 0;
    }

    if (!activeSequences) {
      this._nextExpectedAction = false;
    }
  }

  /**
   * called to set a 1 second timeout on the specified sequence
   *
   * this is so after each key press in the sequence you have 1 second
   * to press the next key before you have to start over
   *
   * @returns void
   */
  _resetSequenceTimer() {
    clearTimeout(this._resetTimer);
    this._resetTimer = setTimeout(() => this._resetSequences(), 1000);
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
  _getMatches(character, modifiers, e, sequenceName, combination, level) {
    const matches = [];
    const action = e.type;

    // if there are no events related to this key
    if (!this._callbacks[character]) {
      return [];
    }

    // if a modifier key is coming up on its own we should allow it
    if (action == "keyup" && _isModifier(character)) {
      modifiers = [character];
    }

    // loop through all callbacks for the key that was pressed
    // and see if any of them match
    for (let i = 0; i < this._callbacks[character].length; ++i) {
      const callback = this._callbacks[character][i];

      // if a sequence name is not specified, but this is a sequence at
      // the wrong level then move onto the next match
      if (
        !sequenceName &&
        callback.seq &&
        this._sequenceLevels[callback.seq] != callback.level
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
        const deleteCombo = !sequenceName && callback.combo == combination;
        const deleteSequence =
          sequenceName &&
          callback.seq == sequenceName &&
          callback.level == level;
        if (deleteCombo || deleteSequence) {
          this._callbacks[character].splice(i, 1);
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
  _fireCallback(callback, e, combo, sequence) {
    // if this event should not happen stop here
    if (this.stopCallback(e, e.target || e.srcElement, combo, sequence)) {
      return;
    }

    if (callback(e, combo) === false) {
      e.preventDefault();
      e.stopPropagation();
    }
  }

  /**
   * handles a key event
   *
   * @param {Event} e
   * @returns void
   */
  _handleKeyEvent(e) {
    const character = this._characterFromEvent(e);
    // no character found then stop
    if (!character) {
      return;
    }

    if (e.type == "keyup" && this._ignoreNextKeyup === character) {
      this._ignoreNextKeyup = false;
      return;
    }

    this.handleKey(character, _eventModifiers(e), e);
  }
}

/**
 * Init the global mousetrap functions
 *
 * This method is needed to allow the global mousetrap functions to work
 * now that mousetrap is a constructor function.
 */
const instance = new Mousetrap(globalDocument);

export { Mousetrap };

export default instance;
