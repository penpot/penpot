;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.inspect-stroke-tokens-test
  (:require
   [app.common.types.token :as cto]
   [app.main.ui.inspect.styles.panels.stroke :as stroke]
   [cljs.test :as t :include-macros true]))

(def ^:private all-sides-sw-3
  (zipmap cto/per-side-stroke-width-keys (repeat "sw-3")))

(t/deftest border-width-shows-the-token-applied-to-every-side
  (t/is (= "sw-3" (stroke/stroke-property-token-name all-sides-sw-3 :border-width))))

(t/deftest border-width-shows-no-token-when-the-sides-differ
  (t/is (nil? (stroke/stroke-property-token-name
               (assoc all-sides-sw-3 :stroke-width-top "sw-10") :border-width)))
  (t/is (nil? (stroke/stroke-property-token-name
               {:stroke-width-top "sw-3"} :border-width))))

(t/deftest border-width-reads-the-legacy-key-of-unmigrated-files
  (t/is (= "sw-3" (stroke/stroke-property-token-name {:stroke-width "sw-3"} :border-width))))

(t/deftest per-side-widths-show-the-token-of-each-side
  (let [applied {:stroke-width-top    "sw-10"
                 :stroke-width-right  "sw-3"
                 :stroke-width-bottom "sw-3"
                 :stroke-width-left   "sw-4"}]
    (t/is (= "sw-10" (stroke/stroke-property-token-name applied :border-block-start-width)))
    (t/is (= "sw-3" (stroke/stroke-property-token-name applied :border-inline-end-width)))
    (t/is (= "sw-3" (stroke/stroke-property-token-name applied :border-block-end-width)))
    (t/is (= "sw-4" (stroke/stroke-property-token-name applied :border-inline-start-width)))))

(t/deftest per-side-widths-without-a-token-show-none
  (t/is (nil? (stroke/stroke-property-token-name
               {:stroke-width-top "sw-10"} :border-inline-end-width))))

(t/deftest border-color-keeps-the-stroke-color-token
  (t/is (= "red" (stroke/stroke-property-token-name {:stroke-color "red"} :border-color))))
