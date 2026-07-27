;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.render-wasm.resources
  "Host-agnostic enumeration of the external resources a scene needs to
  render: which image bytes its shapes reference. Pure data walking — no
  browser or Node dependencies — so the workspace and the headless exporter
  derive the same set from the same source (sibling of
  `app.render-wasm.fallback-fonts`, which does the same for fonts)."
  (:require
   [app.common.types.fills :as types.fills]))

(defn- fill-image-ids
  [fills]
  (some-> fills not-empty types.fills/coerce types.fills/get-image-ids))

(defn- stroke-image-ids
  [strokes]
  (keep (comp :id :stroke-image) strokes))

(defn- text-image-ids
  "Image-fill ids referenced by a text shape's span fills."
  [content]
  (when content
    (->> (tree-seq :children :children content)
         (mapcat :fills)
         (keep (comp :id :fill-image)))))

(defn shape-image-ids
  "Distinct image ids referenced by one shape: its fills, its strokes' image
  fills, and (for texts) its spans' image fills."
  [shape]
  (-> #{}
      (into (fill-image-ids (:fills shape)))
      (into (stroke-image-ids (:strokes shape)))
      ;; `:content` is only a text tree for text shapes (paths reuse the key
      ;; for geometry).
      (into (when (= :text (:type shape))
              (text-image-ids (:content shape))))))

(defn scene-image-ids
  "Distinct image ids referenced anywhere in an `objects` map."
  [scene]
  (into #{} (mapcat shape-image-ids) (vals scene)))
