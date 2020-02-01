;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2015-2020 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2015-2020 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns uxbox.main.ui.workspace.sidebar.options.rect
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.geom :as geom]
   [uxbox.main.store :as st]
   [uxbox.main.ui.workspace.sidebar.options.fill :refer [fill-menu]]
   [uxbox.main.ui.workspace.sidebar.options.stroke :refer [stroke-menu]]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.math :as math]))

(mf/defc measures-menu
  [{:keys [shape] :as props}]
  (let [on-size-change
        (fn [event attr]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (udw/update-rect-dimensions (:id shape) attr value))))

        on-proportion-lock-change
        (fn [event]
          (st/emit! (udw/toggle-shape-proportion-lock (:id shape))))

        on-position-change
        (fn [event attr]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer))]
            (st/emit! (udw/update-position (:id shape) {attr value}))))

        on-rotation-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-integer 0))]
            (st/emit! (udw/update-shape (:id shape) {:rotation value}))))

        on-radius-change
        (fn [event]
          (let [value (-> (dom/get-target event)
                          (dom/get-value)
                          (d/parse-double 0))]
            (st/emit! (udw/update-shape (:id shape) {:rx value :ry value}))))

        on-width-change #(on-size-change % :width)
        on-height-change #(on-size-change % :height)
        on-pos-x-change #(on-position-change % :x)
        on-pos-y-change #(on-position-change % :y)]

    [:div.element-set
     [:div.element-set-title (tr "workspace.options.measures")]
     [:div.element-set-content
      [:span (tr "workspace.options.size")]

      ;; WIDTH & HEIGHT
      [:div.row-flex
       [:div.input-element.pixels
        [:input.input-text {:type "number"
                            :min "0"
                            :on-change on-width-change
                            :value (-> (:width shape)
                                       (math/precision 2)
                                       (d/coalesce-str "0"))}]]

       [:div.lock-size {:class (when (:proportion-lock shape) "selected")
                        :on-click on-proportion-lock-change}
        (if (:proportion-lock shape)
          i/lock
          i/unlock)]

       [:div.input-element.pixels
        [:input.input-text {:type "number"
                            :min "0"
                            :on-change on-height-change
                            :value (-> (:height shape)
                                       (math/precision 2)
                                       (d/coalesce-str "0"))}]]]

      ;; POSITION
      [:span (tr "workspace.options.position")]
      [:div.row-flex
       [:div.input-element.pixels
        [:input.input-text {:placeholder "x"
                            :type "number"
                            :on-change on-pos-x-change
                            :value (-> (:x shape)
                                       (math/precision 2)
                                       (d/coalesce-str "0"))}]]
       [:div.input-element.pixels
        [:input.input-text {:placeholder "y"
                            :type "number"
                            :on-change on-pos-y-change
                            :value (-> (:y shape)
                                       (math/precision 2)
                                       (d/coalesce-str "0"))}]]]

      [:span (tr "workspace.options.rotation-radius")]
      [:div.row-flex
       [:div.input-element.degrees
        [:input.input-text {:placeholder ""
                            :type "number"
                            :min 0
                            :max 360
                            :on-change on-rotation-change
                            :value (-> (:rotation shape 0)
                                       (math/precision 2)
                                       (d/coalesce-str "0"))}]]

       [:div.input-element.pixels
        [:input.input-text
         {:placeholder "rx"
          :type "number"
          :on-change on-radius-change
          :value (-> (:rx shape)
                     (math/precision 2)
                     (d/coalesce-str "0"))}]]]]]))


(mf/defc options
  [{:keys [shape] :as props}]
  [:div
   [:& measures-menu {:shape shape}]
   [:& fill-menu {:shape shape}]
   [:& stroke-menu {:shape shape}]])
