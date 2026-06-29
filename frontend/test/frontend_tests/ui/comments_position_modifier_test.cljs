;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.comments-position-modifier-test
  (:require
   [app.common.geom.matrix :as gmt]
   [app.common.geom.point :as gpt]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.ui.workspace.viewport.comments :as vc]
   [cljs.test :as t :include-macros true]))

(defn- frame
  [id]
  (cts/setup-shape {:id id :type :frame :name "Board"
                    :x 100 :y 100 :width 200 :height 150}))

(t/deftest svg-position-modifier
  (let [frame-id (uuid/next)
        objects  {frame-id (frame frame-id)}]
    (t/testing "yields the frame translation from the legacy modifiers"
      (let [modifiers {frame-id {:modifiers (ctm/move-modifiers (gpt/point 10 20))}}]
        (t/is (= (gmt/translate-matrix (gpt/point 10 20))
                 (#'vc/svg-position-modifier objects modifiers frame-id)))))

    (t/testing "nil when the frame has no active modifier"
      (t/is (nil? (#'vc/svg-position-modifier objects {} frame-id))))

    (t/testing "nil when the frame does not exist"
      (let [modifiers {frame-id {:modifiers (ctm/move-modifiers (gpt/point 10 20))}}]
        (t/is (nil? (#'vc/svg-position-modifier {} modifiers frame-id)))))))

(t/deftest wasm-position-modifier
  (let [frame-id (uuid/next)
        objects  {frame-id (frame frame-id)}]
    (t/testing "yields the frame translation from the wasm transform"
      (let [wasm-mods {frame-id (gmt/translate-matrix (gpt/point 10 20))}]
        (t/is (= (gmt/translate-matrix (gpt/point 10 20))
                 (#'vc/wasm-position-modifier objects wasm-mods frame-id)))))

    (t/testing "nil when the frame has no active transform"
      (t/is (nil? (#'vc/wasm-position-modifier objects {} frame-id))))))
