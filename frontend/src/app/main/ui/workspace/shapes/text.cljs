;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.workspace.shapes.text
  (:require
   [app.common.data :as d]
   [app.common.math :as mth]
   [app.main.data.workspace.texts :as dwt]
   [app.main.refs :as refs]
   [app.main.ui.shapes.shape :refer [shape-container]]
   [app.main.ui.shapes.text :as text]
   [debug :refer [debug?]]
   [rumext.alpha :as mf]))

;; --- Text Wrapper for workspace
(mf/defc text-wrapper
  {::mf/wrap-props false}
  [props]
  (let [shape (unchecked-get props "shape")

        text-modifier-ref
        (mf/use-memo (mf/deps (:id shape)) #(refs/workspace-text-modifier-by-id (:id shape)))

        text-modifier
        (mf/deref text-modifier-ref)

        shape (cond-> shape
                (some? text-modifier)
                (dwt/apply-text-modifier text-modifier))]

    [:> shape-container {:shape shape}
     [:*
      [:g.text-shape
       [:& text/text-shape {:shape shape}]]

      (when (and (debug? :text-outline) (d/not-empty? (:position-data shape)))
        (for [data (:position-data shape)]
          (let [{:keys [x y width height]} data]
            [:*
             ;; Text fragment bounding box
             [:rect {:x x
                     :y (- y height)
                     :width width
                     :height height
                     :style {:fill "none" :stroke "red"}}]

             ;; Text baselineazo
             [:line {:x1 (mth/round x)
                     :y1 (mth/round (- (:y data) (:height data)))
                     :x2 (mth/round (+ x width))
                     :y2 (mth/round (- (:y data) (:height data)))
                     :style {:stroke "blue"}}]])))]]))
