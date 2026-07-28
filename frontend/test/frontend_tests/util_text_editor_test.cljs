;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.util-text-editor-test
  (:require
   [app.util.text-editor :as te]
   [cljs.test :as t :include-macros true]))

(t/deftest get-editor-block-data-returns-nil-for-nil-block
  (t/is (nil? (te/get-editor-block-data nil)))
  (t/is (nil? (te/get-editor-block-data js/undefined))))

(t/deftest get-editor-block-type-returns-nil-for-nil-block
  (t/is (nil? (te/get-editor-block-type nil)))
  (t/is (nil? (te/get-editor-block-type js/undefined))))
