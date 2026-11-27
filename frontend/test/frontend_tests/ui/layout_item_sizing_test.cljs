;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.layout-item-sizing-test
  (:require
   [app.main.ui.workspace.sidebar.options.menus.layout-item :as layout-item]
   [app.main.ui.workspace.sidebar.options.shapes.multiple :as multiple]
   [cljs.test :as t :include-macros true]))

(defn- rect
  [id sizing]
  (cond-> {:id id :type :rect}
    (some? sizing) (assoc :layout-item-h-sizing sizing
                          :layout-item-v-sizing sizing)))

(defn- sizing-values
  [shapes]
  (let [[_ids values _tokens] (multiple/get-attrs* shapes {} :layout-item)]
    (select-keys values [:layout-item-h-sizing :layout-item-v-sizing])))

(t/deftest a-shape-without-sizing-shows-fix-selected
  (t/is (= "fix" (layout-item/sizing-option nil))))

(t/deftest a-shape-with-sizing-shows-its-own-option
  (t/is (= "fix" (layout-item/sizing-option :fix)))
  (t/is (= "fill" (layout-item/sizing-option :fill)))
  (t/is (= "auto" (layout-item/sizing-option :auto))))

(t/deftest mixed-sizing-shows-no-option-selected
  (t/is (= "multiple" (layout-item/sizing-option :multiple))))

(t/deftest shapes-without-sizing-and-fixed-shapes-show-fix
  (t/is (= {:layout-item-h-sizing :fix :layout-item-v-sizing :fix}
           (sizing-values [(rect :rect-1 nil) (rect :rect-2 :fix)]))))

(t/deftest shapes-without-sizing-show-fix
  (t/is (= {:layout-item-h-sizing :fix :layout-item-v-sizing :fix}
           (sizing-values [(rect :rect-1 nil) (rect :rect-2 nil)]))))

(t/deftest shapes-without-sizing-and-fill-shapes-show-mixed
  (t/is (= {:layout-item-h-sizing :multiple :layout-item-v-sizing :multiple}
           (sizing-values [(rect :rect-1 nil) (rect :rect-2 :fill)]))))
