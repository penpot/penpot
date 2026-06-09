;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.viewer.viewport-wasm
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.geom.point :as gpt]
   [app.main.render-viewer-wasm :as rwv]
   [app.main.ui.viewer.shapes :as shapes]
   [app.main.ui.viewer.viewport-common :as vpc]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(defn- canvas-dimensions
  [scale size]
  {:width (js/Math.round (* scale (:base-width size)))
   :height (js/Math.round (* scale (:base-height size)))})

(defn- frame-hotspots-props
  "frame-hotspots* uses ::mf/wrap-props false and expects string keys."
  [prepared prepared-all prepared-frame shape-filter]
  (let [props #js {"objects" prepared
                   "all-objects" prepared-all
                   "frame" prepared-frame}]
    (when shape-filter
      (obj/set! props "shape-filter" shape-filter))
    props))

(mf/defc wasm-hotspots-svg
  [{:keys [vbox size class prepared prepared-all prepared-frame shape-filter]}]
  [:svg {:view-box vbox
         :width (:width size)
         :height (:height size)
         :version "1.1"
         :xmlnsXlink "http://www.w3.org/1999/xlink"
         :xmlns "http://www.w3.org/2000/svg"
         :fill "none"
         :style {:position "absolute"
                 :top 0
                 :left 0}
         :class class}
   [:& shapes/frame-hotspots*
    (frame-hotspots-props prepared prepared-all prepared-frame shape-filter)]])

(mf/defc wasm-layer
  [{:keys [canvas-ref scale size vbox svg-props]}]
  (let [{:keys [width height]} (canvas-dimensions scale size)]
    [:div {:style {:position "absolute"
                   :top 0
                   :left 0}}
     [:canvas {:ref canvas-ref :width width :height height :style {:width "100%"
                                                                   :height "100%"
                                                                   :background "transparent"
                                                                   :pointer-events "none"}}]
     [:& wasm-hotspots-svg (assoc svg-props :vbox vbox :size size)]]))

(defn- fixed-scroll-layer-ids
  [objects frame-id has-fixed?]
  (let [frame-subtree-ids (into #{} (cfh/get-children-ids-with-self objects frame-id))
        fixed-mask-ids (when has-fixed? (vpc/frame-fixed-mask-ids objects frame-id))
        fixed-mask-set (or fixed-mask-ids #{})
        not-fixed-include-ids
        (when has-fixed?
          (into []
                (distinct
                 (conj (->> frame-subtree-ids
                            (remove #(contains? fixed-mask-set %)))
                       frame-id))))
        fixed-include-ids
        (when has-fixed?
          (vec (conj (or fixed-mask-ids #{}) frame-id)))
        fixed-clear-fills-ids (when has-fixed? #{frame-id})]
    {:fixed-mask-set fixed-mask-set
     :not-fixed-include-ids not-fixed-include-ids
     :fixed-include-ids fixed-include-ids
     :fixed-clear-fills-ids fixed-clear-fills-ids}))

(mf/defc viewport-wasm
  {::mf/wrap [mf/memo]
   ::mf/wrap-props false}
  [props]
  (let [page    (unchecked-get props "page")
        frame   (unchecked-get props "frame")
        base    (unchecked-get props "base")
        offset  (unchecked-get props "offset")
        size    (unchecked-get props "size")
        delta   (or (unchecked-get props "delta") (gpt/point 0 0))
        vbox    (:vbox size)
        fixed?  (true? (unchecked-get props "fixed?"))

        fixed-layer-ref      (mf/use-ref nil)
        not-fixed-wasm-ref   (mf/use-ref nil)
        fixed-wasm-ref       (mf/use-ref nil)

        objects  (:objects page)
        frame-id (:id frame)
        scale    (vpc/viewer-scale size)
        page-id  (:id page)

        frame   (cond-> frame fixed? (assoc :fixed-scroll true))
        objects (cond-> objects fixed? (assoc-in [frame-id :fixed-scroll] true))

        has-fixed?
        (and (not fixed?)
             (some #(cfh/fixed-scroll? (get objects %))
                   (cfh/get-children-ids objects frame-id)))

        prepared
        (mf/with-memo [objects frame size delta]
          (vpc/prepare-objects frame size delta objects))

        prepared-all
        (mf/with-memo [objects size delta]
          (vpc/prepare-page-objects objects size delta))

        {:keys [fixed-mask-set not-fixed-include-ids fixed-include-ids fixed-clear-fills-ids]}
        (mf/with-memo [objects frame-id has-fixed?]
          (fixed-scroll-layer-ids objects frame-id has-fixed?))

        prepared-frame (get prepared frame-id)

        svg-base {:prepared prepared
                  :prepared-all prepared-all
                  :prepared-frame prepared-frame}]

    (rwv/use-viewer-wasm-viewport!
     page-id objects size scale frame-id
     not-fixed-wasm-ref fixed-wasm-ref
     (when has-fixed? fixed-layer-ref)
     not-fixed-include-ids fixed-include-ids fixed-clear-fills-ids)

    [:& (mf/provider shapes/base-frame-ctx) {:value (get prepared-all (:id base))}
     [:& (mf/provider shapes/frame-offset-ctx) {:value offset}
      [:*
       [:& wasm-layer
        {:canvas-ref not-fixed-wasm-ref
         :scale scale
         :size size
         :vbox vbox
         :svg-props (assoc svg-base
                           :class (if has-fixed?
                                    (stl/css :not-fixed)
                                    (when fixed? (stl/css :fixed)))
                           :shape-filter (when has-fixed?
                                           #(not (contains? fixed-mask-set %))))}]

       (when has-fixed?
         [:div {:ref fixed-layer-ref}
          [:& wasm-layer
           {:canvas-ref fixed-wasm-ref
            :scale scale
            :size size
            :vbox vbox
            :svg-props (assoc svg-base
                              :class (stl/css :not-fixed)
                              :shape-filter #(contains? fixed-mask-set %))}]])]]]))
