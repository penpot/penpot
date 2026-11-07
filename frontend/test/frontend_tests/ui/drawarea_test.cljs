;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.drawarea-test
  (:require
   [app.common.types.shape :as cts]
   [app.main.ui.workspace.viewport.drawarea :as drawarea]
   [cljs.test :as t :include-macros true]))

(def ^:private box-types
  [:rect :frame :circle :text])

(t/deftest box-drawings-get-the-generic-overlay-for-any-tool
  (doseq [type box-types
          tool [:path :curve type nil]]
    (let [shape (cts/setup-shape {:type type :x 10 :y 10 :width 0.01 :height 0.01})]
      (t/is (= :generic (drawarea/draw-area-kind tool shape))
            (str "type " type " with tool " tool)))))

(t/deftest path-drawings-follow-the-active-tool
  (let [shape (cts/setup-shape {:type :path})]
    (t/is (= :path (drawarea/draw-area-kind :path shape)))
    (t/is (= :curve (drawarea/draw-area-kind :curve shape)))
    (t/is (nil? (drawarea/draw-area-kind :line shape)))
    (t/is (nil? (drawarea/draw-area-kind nil shape)))))
