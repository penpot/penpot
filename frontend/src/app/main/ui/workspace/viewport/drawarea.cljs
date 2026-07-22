;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.viewport.drawarea
  "Drawing components."
  (:require
   [app.common.data.macros :as dm]
   [app.common.math :as mth]
   [app.common.types.path :as path]
   [app.main.refs :as refs]
   [app.main.ui.shapes.path :refer [path-shape]]
   [app.main.ui.workspace.shapes :as shapes]
   [app.main.ui.workspace.shapes.path.editor :refer [path-editor*]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(defn- make-edit-path-ref [id]
  (let [get-fn #(dm/get-in % [:edit-path id])]
    (l/derived get-fn refs/workspace-local)))

(def ^:private edit-fill-opacity
  "Fill opacity used while editing a path."
  0.8)

(def ^:private synced-edit-attrs
  "Visual attributes copied into the live editing shape."
  [:strokes :shadow :blur :background-blur :opacity :blend-mode])

(defn- dim-fills
  [fills]
  (mapv (fn [fill]
          (update fill :fill-opacity #(* (or % 1) edit-fill-opacity)))
        fills))

(defn path-edit-shape
  "Builds the path shape rendered during editing."
  [drawing-obj stored]
  (-> (cond-> (merge drawing-obj (select-keys stored synced-edit-attrs))
        (seq (:fills stored)) (assoc :fills (:fills stored)))
      (update :fills dim-fills)))

(mf/defc generic-draw-area*
  {::mf/private true}
  [{:keys [shape zoom]}]
  (let [{:keys [x y width height]} (get shape :selrect)]
    (when (and x y
               (not (mth/nan? x))
               (not (mth/nan? y)))

      [:rect.main {:x x :y y
                   :width width
                   :height height
                   :style {:stroke "var(--color-accent-tertiary)"
                           :fill "none"
                           :stroke-width (/ 1 zoom)}}])))

(mf/defc path-draw-area*
  {::mf/private true}
  [{:keys [shape] :as props}]
  (let [shape-id
        (dm/get-prop shape :id)

        edit-path-ref
        (mf/with-memo [shape-id]
          (make-edit-path-ref shape-id))

        edit-path-state
        (mf/deref edit-path-ref)

        props
        (mf/spread-props props {:state edit-path-state})]

    [:> path-editor* props]))

(mf/defc draw-area*
  [{:keys [shape zoom tool] :as props}]
  (let [shape-id
        (dm/get-prop shape :id)

        edit-path-ref
        (mf/with-memo [shape-id]
          (make-edit-path-ref shape-id))

        ;; Keep command indices unchanged while applying drag modifiers.
        dragging?
        (some? (:content-modifiers (mf/deref edit-path-ref)))

        ;; Close rendered subpaths while keeping editor content untouched.
        render-shape
        (mf/with-memo [shape dragging?]
          (if (and (= :path (dm/get-prop shape :type)) (not dragging?))
            (update shape :content #(-> % path/close-subpaths path/close-loops))
            shape))]
    [:g.draw-area
     [:g {:style {:pointer-events "none"}}
      [:& shapes/shape-wrapper {:shape render-shape}]]

     (cond
       (= tool :path)
       [:> path-draw-area* props]

       (= tool :curve)
       [:& path-shape {:shape shape :zoom zoom}]

       (= (:type shape) :path)
       nil

       :else
       [:> generic-draw-area* props])]))
