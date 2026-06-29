;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.ui.comments-position-modifier-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes :as gsh]
   [app.common.math :as mth]
   [app.common.types.modifiers :as ctm]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.comments :as dwcm]
   [cljs.test :as t :include-macros true]))

(defn- frame
  [id]
  (cts/setup-shape {:id id :type :frame :name "Board"
                    :x 100 :y 100 :width 200 :height 150}))

(defn- close-point?
  [a b]
  (and (mth/close? (:x a) (:x b))
       (mth/close? (:y a) (:y b))))

(t/deftest frame-pin-transform-move
  (let [f    (frame (uuid/next))
        mods (ctm/move-modifiers (gpt/point 10 20))
        m    (dwcm/frame-pin-transform f mods nil)]
    (t/testing "the comment follows the frame translation"
      (t/is (close-point? (gpt/point 160 170)
                          (gpt/transform (gpt/point 150 150) m))))))

(t/deftest frame-pin-transform-rotation
  (let [f      (frame (uuid/next))
        center (gsh/shape->center f)
        mods   (ctm/rotation (ctm/empty) center 90)
        m      (dwcm/frame-pin-transform f mods nil)
        p      (gpt/point 150 150)]
    (t/testing "the comment rotates around the frame center"
      (t/is (close-point? (gpt/transform p (ctm/modifiers->transform mods))
                          (gpt/transform p m))))))

(t/deftest frame-pin-transform-resize
  (let [f    (frame (uuid/next))
        mods (ctm/resize (ctm/empty) (gpt/point 2 2) (gpt/point 100 100))
        m    (dwcm/frame-pin-transform f mods nil)
        p    (gpt/point 150 150)]
    (t/testing "the comment keeps its position without scaling"
      (t/is (close-point? p (gpt/transform p m))))
    (t/testing "the comment is not scaled along with the frame"
      (t/is (not (close-point? (gpt/transform p (ctm/modifiers->transform mods))
                               (gpt/transform p m)))))))

(t/deftest frame-pin-transform-without-transform
  (let [f (frame (uuid/next))]
    (t/testing "no active transform yields no matrix"
      (t/is (nil? (dwcm/frame-pin-transform f nil nil))))))
