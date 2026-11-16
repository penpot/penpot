;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.text-v3-editor-test
  "Keys of the WASM text editor input."
  (:require
   [app.main.ui.workspace.shapes.text.v3-editor :as v3-editor]
   [cljs.test :as t :include-macros true]))

(t/deftest escape-that-cancels-native-composition-keeps-text-editing
  ;; Fcitx Pinyin ends composition before releasing Escape. That key-up no
  ;; longer reports isComposing, so remember that its key-down belonged to IME.
  (t/is (false? (@#'v3-editor/editor-escape?
                 #js {:key "Escape" :isComposing false :keyCode 27} true)))
  (t/is (false? (@#'v3-editor/editor-escape?
                 #js {:key "Escape" :isComposing true :keyCode 27} false)))
  (t/is (false? (@#'v3-editor/editor-escape?
                 #js {:key "Escape" :isComposing false :keyCode 229} false))))

(t/deftest ordinary-escape-still-exits-text-editing
  (t/is (true? (@#'v3-editor/editor-escape?
                #js {:key "Escape" :isComposing false :keyCode 27} false)))
  (t/is (false? (@#'v3-editor/editor-escape?
                 #js {:key "Enter" :isComposing false :keyCode 13} false))))
