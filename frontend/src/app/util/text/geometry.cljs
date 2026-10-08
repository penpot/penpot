;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.util.text.geometry
  (:require
   [app.common.geom.point :as gpt]
   [app.common.types.text.japanese-layout :as jl]))

(defn resolve-size
  "Auto-height grows along the block axis: height horizontally, width vertically."
  [selrect grow-type content dimension]
  (when (or (= :fixed grow-type) (some? dimension))
    (let [vertical? (jl/vertical-text-content? content)]
      {:width (if (or (= :fixed grow-type)
                      (and (= :auto-height grow-type) (not vertical?)))
                (:width selrect)
                (:width dimension))
       :height (if (or (= :fixed grow-type)
                       (and (= :auto-height grow-type) vertical?))
                 (:height selrect)
                 (:height dimension))})))

(defn- block-anchor
  [content]
  (if (jl/vertical-text-content? content)
    (case (:vertical-align content) "center" 0.5 "bottom" 0 1)
    (case (:vertical-align content) "center" 0.5 "bottom" 1 0)))

(defn resize-origin
  "Keep the text's block alignment anchored when its measured size changes."
  [{:keys [points content]}]
  (gpt/lerp (nth points 0)
            (nth points (if (jl/vertical-text-content? content) 1 3))
            (block-anchor content)))

(defn live-rect
  "Bounds of live editor content, before its geometry is committed."
  [selrect grow-type content dimension]
  (if-let [{:keys [width height]} (resolve-size selrect grow-type content dimension)]
    (let [vertical? (jl/vertical-text-content? content)
          anchor    (block-anchor content)]
      (assoc selrect
             :x (+ (:x selrect) (if vertical? (* anchor (- (:width selrect) width)) 0))
             :y (+ (:y selrect) (if vertical? 0 (* anchor (- (:height selrect) height))))
             :width width
             :height height))
    selrect))
