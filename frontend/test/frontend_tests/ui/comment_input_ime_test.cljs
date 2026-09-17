;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.comment-input-ime-test
  "Regression tests for https://github.com/penpot/penpot/issues/11757.

  Confirming a Japanese IME composition with Enter duplicated the text
  because the comment-input keydown handler treated every Enter as a
  Penpot line-break action. The handler now bypasses its custom key
  processing as a whole while the keydown belongs to an active IME
  composition (nativeEvent.isComposing or keyCode 229, mirroring the
  v3 text-editor precedent from #10498)."
  (:require
   [app.main.ui.comments :as cmt]
   [cljs.test :as t :include-macros true]))

(defn- keydown-event
  ([key]
   (keydown-event key false 13))
  ([key composing? key-code]
   #js {:key key
        :keyCode key-code
        :nativeEvent #js {:isComposing composing?}}))

(t/deftest composing-event-predicate
  (t/testing "a plain keydown is not a composition event"
    (t/is (false? (boolean (cmt/composing-event? (keydown-event "Enter"))))))

  (t/testing "a composing Enter is detected via nativeEvent.isComposing"
    (t/is (true? (boolean (cmt/composing-event? (keydown-event "Enter" true 13))))))

  (t/testing "keyCode 229 bypasses even when isComposing is not set (macOS ordering)"
    (t/is (true? (boolean (cmt/composing-event? (keydown-event "Enter" false 229)))))
    (t/is (true? (boolean (cmt/composing-event? (keydown-event "Escape" false 229)))))
    (t/is (true? (boolean (cmt/composing-event? (keydown-event "ArrowDown" false 229))))))

  (t/testing "ordinary editor keys are not compositions"
    (t/is (false? (boolean (cmt/composing-event? (keydown-event "Escape" false 27)))))
    (t/is (false? (boolean (cmt/composing-event? (keydown-event "ArrowDown" false 40)))))
    (t/is (false? (boolean (cmt/composing-event? (keydown-event "Backspace" false 8)))))))
