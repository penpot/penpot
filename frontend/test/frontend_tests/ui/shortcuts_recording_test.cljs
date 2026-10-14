;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.shortcuts-recording-test
  (:require
   [app.main.ui.shortcuts :as ss]
   [cljs.test :as t :include-macros true]))

(def ^:private keyboard-event->mousetrap
  @#'ss/keyboard-event->mousetrap)

(defn- key-event
  [props]
  (clj->js (merge {:altKey false :shiftKey false :ctrlKey false :metaKey false}
                  props)))

(t/deftest records-alt-digit-from-key-code
  ;; macOS Option+Shift+5 types "ﬁ"; Mousetrap matches it as "5"
  (t/is (= "alt+shift+5"
           (keyboard-event->mousetrap
            (key-event {:key "ﬁ" :code "Digit5" :keyCode 53 :altKey true :shiftKey true})))))

(t/deftest records-alt-letter-from-key-code
  ;; macOS Option+A types "å"; Mousetrap matches it as "a"
  (t/is (= "alt+a"
           (keyboard-event->mousetrap
            (key-event {:key "å" :code "KeyA" :keyCode 65 :altKey true})))))

(t/deftest records-shift-digit-from-key-code
  ;; US Shift+0 types ")"; Mousetrap matches it as "0"
  (t/is (= "shift+0"
           (keyboard-event->mousetrap
            (key-event {:key ")" :code "Digit0" :keyCode 48 :shiftKey true})))))

(t/deftest records-modified-letter-from-key-code
  ;; Russian layout V types "м"; Mousetrap matches Alt+V as "v"
  (t/is (= "alt+v"
           (keyboard-event->mousetrap
            (key-event {:key "м" :code "KeyV" :keyCode 86 :altKey true})))))

(t/deftest records-unmodified-key-from-glyph
  ;; without modifiers Mousetrap matches the typed glyph on keypress
  (t/is (= "&"
           (keyboard-event->mousetrap
            (key-event {:key "&" :code "Digit1" :keyCode 49}))))
  (t/is (= "ñ"
           (keyboard-event->mousetrap
            (key-event {:key "ñ" :code "" :keyCode 0})))))

(t/deftest records-shift-punctuation-from-code
  ;; Shift+. types ":" and Shift+= types "+" (US); the key is recorded
  (t/is (= "shift+."
           (keyboard-event->mousetrap
            (key-event {:key ":" :code "Period" :keyCode 190 :shiftKey true}))))
  (t/is (= "shift+="
           (keyboard-event->mousetrap
            (key-event {:key "+" :code "Equal" :keyCode 187 :shiftKey true})))))
