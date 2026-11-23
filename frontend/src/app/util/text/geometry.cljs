;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.util.text.geometry
  (:require
   [app.common.geom.point :as gpt]
   [app.util.text.writing-mode :as wm]))

(defn- live-axis
  "Measured axis, or the committed one when the measure is missing or not
  positive, so a failed WASM measure never shows as `0`."
  [measured committed]
  (if (and (number? measured) (pos? measured)) measured committed))

(defn resolve-size
  "Auto-height grows along the block axis: height horizontally, width vertically."
  [selrect grow-type content dimension]
  (when (or (= :fixed grow-type) (some? dimension))
    (let [vertical? (wm/vertical-text-content? content)]
      {:width (if (or (= :fixed grow-type)
                      (and (= :auto-height grow-type) (not vertical?)))
                (:width selrect)
                (live-axis (:width dimension) (:width selrect)))
       :height (if (or (= :fixed grow-type)
                       (and (= :auto-height grow-type) vertical?))
                 (:height selrect)
                 (live-axis (:height dimension) (:height selrect)))})))

(defn- block-anchor
  "Share of the width a vertical text grows by to its left: its right edge
  stays for top alignment, its center for center, its left edge for bottom."
  [content]
  (case (:vertical-align content) "center" 0.5 "bottom" 0 1))

(defn resize-origin
  "Point that stays put when a text's measured size changes. Vertical text
  keeps the block-axis edge its alignment sets; horizontal text keeps its
  top-left corner, as the WASM renderer does."
  [{:keys [points content]}]
  (if (wm/vertical-text-content? content)
    (gpt/lerp (nth points 0) (nth points 1) (block-anchor content))
    (nth points 0)))

(defn live-rect
  "Bounds of live editor content, before its geometry is committed."
  [selrect grow-type content dimension]
  (if-let [{:keys [width height]} (resolve-size selrect grow-type content dimension)]
    (cond-> (assoc selrect :width width :height height)
      (wm/vertical-text-content? content)
      (update :x + (* (block-anchor content) (- (:width selrect) width))))
    selrect))
