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
   [app.render-wasm.api :as wasm.api]
   [rumext.v2 :as mf]))

(defn- canvas-dimensions
  "Physical canvas pixels (CSS layout size × DPR), matching the workspace WASM path."
  [scale size]
  (let [css-w (js/Math.round (* scale (:base-width size)))
        css-h (js/Math.round (* scale (:base-height size)))
        dpr   (wasm.api/get-dpr)]
    {:width  (js/Math.round (* css-w dpr))
     :height (js/Math.round (* css-h dpr))}))

(mf/defc wasm-hotspots-svg*
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
   [:> shapes/frame-hotspots*
    {:objects prepared
     :all-objects prepared-all
     :frame prepared-frame
     :shape-filter shape-filter}]])

(mf/defc wasm-layer*
  [{:keys [canvas-ref scale size vbox
           prepared prepared-all prepared-frame class shape-filter]}]
  (let [{:keys [width height]} (canvas-dimensions scale size)]
    [:div {:style {:position "absolute"
                   :top 0
                   :left 0}}
     [:canvas {:ref canvas-ref :width width :height height :style {:width "100%"
                                                                   :height "100%"
                                                                   :background "transparent"
                                                                   :pointer-events "none"}}]
     [:> wasm-hotspots-svg* {:vbox vbox
                             :size size
                             :class class
                             :prepared prepared
                             :prepared-all prepared-all
                             :prepared-frame prepared-frame
                             :shape-filter shape-filter}]]))

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

(mf/defc viewport-wasm*
  {::mf/wrap [mf/memo]}
  [{:keys [page frame base offset size delta is-fixed]}]
  (let [delta     (or delta (gpt/point 0 0))
        vbox      (:vbox size)
        is-fixed  (true? is-fixed)

        fixed-layer-ref      (mf/use-ref nil)
        not-fixed-wasm-ref   (mf/use-ref nil)
        fixed-wasm-ref       (mf/use-ref nil)

        objects  (:objects page)
        frame-id (:id frame)
        scale    (vpc/viewer-scale size)
        page-id  (:id page)

        frame   (cond-> frame is-fixed (assoc :fixed-scroll true))
        objects (cond-> objects is-fixed (assoc-in [frame-id :fixed-scroll] true))

        has-fixed?
        (and (not is-fixed)
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

        not-fixed-props
        (mf/props {:prepared prepared
                   :prepared-all prepared-all
                   :prepared-frame prepared-frame
                   :canvas-ref not-fixed-wasm-ref
                   :scale scale
                   :size size
                   :vbox vbox
                   :class (if has-fixed?
                            (stl/css :not-fixed)
                            (when is-fixed (stl/css :fixed)))
                   :shape-filter (when has-fixed?
                                   #(not (contains? fixed-mask-set %)))})

        fixed-props
        (mf/props {:prepared prepared
                   :prepared-all prepared-all
                   :prepared-frame prepared-frame
                   :canvas-ref fixed-wasm-ref
                   :scale scale
                   :size size
                   :vbox vbox
                   :class (stl/css :not-fixed)
                   :shape-filter #(contains? fixed-mask-set %)})]

    (rwv/use-viewer-wasm-viewport!
     page-id objects size scale frame-id
     not-fixed-wasm-ref fixed-wasm-ref
     (when has-fixed? fixed-layer-ref)
     not-fixed-include-ids fixed-include-ids fixed-clear-fills-ids)

    [:& (mf/provider shapes/base-frame-ctx) {:value (get prepared-all (:id base))}
     [:& (mf/provider shapes/frame-offset-ctx) {:value offset}
      [:*
       [:> wasm-layer* not-fixed-props]

       (when has-fixed?
         [:div {:ref fixed-layer-ref}
          [:> wasm-layer* fixed-props]])]]]))
