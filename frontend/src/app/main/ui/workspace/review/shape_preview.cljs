;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.workspace.review.shape-preview
  "A small drawing of a layer, for the card of a change in the details of
  a branch review."
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.render :as render]
   [rumext.v2 :as mf]))

(defn- shape-bounds
  "Axis-aligned bounds framing a shape, taken from its rotated corner
  `:points` when present, else its `:selrect`."
  [shape]
  (let [pts (:points shape)]
    (if (and (sequential? pts) (seq pts))
      (let [xs (keep :x pts) ys (keep :y pts)
            x (apply min xs) y (apply min ys)]
        {:x x :y y :width (- (apply max xs) x) :height (- (apply max ys) y)})
      (let [sr (:selrect shape)]
        {:x (:x sr 0) :y (:y sr 0) :width (:width sr 1) :height (:height sr 1)}))))

(mf/defc shape-preview*
  "Renders `shape` to a fitted SVG with the real shape renderers. `objects`
  supplies its children (a board or group draws them)."
  [{:keys [shape objects]}]
  (let [{:keys [x y width height]} (shape-bounds shape)
        width   (max 1 width)
        height  (max 1 height)
        objects (mf/with-memo [shape objects]
                  (assoc (or objects {}) (:id shape) shape))
        wrapper (mf/with-memo [objects] (render/shape-wrapper-factory objects))]
    [:svg {:class (stl/css :shape-preview)
           :viewBox (dm/str x " " y " " width " " height)
           :preserveAspectRatio "xMidYMid meet"
           :xmlns "http://www.w3.org/2000/svg"
           :fill "none"}
     [:> wrapper {:shape shape}]]))
