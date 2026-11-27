;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.layout-item-sizing-test
  (:require
   [app.main.ui.workspace.sidebar.options.menus.layout-item :as layout-item]
   [cljs.test :as t :include-macros true]))

(t/deftest a-shape-without-sizing-shows-fix-selected
  (t/is (= "fix" (layout-item/sizing-option nil))))

(t/deftest a-shape-with-sizing-shows-its-own-option
  (t/is (= "fix" (layout-item/sizing-option :fix)))
  (t/is (= "fill" (layout-item/sizing-option :fill)))
  (t/is (= "auto" (layout-item/sizing-option :auto))))

(t/deftest mixed-sizing-shows-no-option-selected
  (t/is (= "multiple" (layout-item/sizing-option :multiple))))
