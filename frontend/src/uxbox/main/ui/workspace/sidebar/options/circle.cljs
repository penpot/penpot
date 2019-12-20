;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.sidebar.options.circle
  (:require
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.geom :as geom]
   [uxbox.main.store :as st]
   [uxbox.main.ui.workspace.sidebar.options.fill :refer [fill-menu]]
   [uxbox.main.ui.workspace.sidebar.options.stroke :refer [stroke-menu]]
   [uxbox.util.data :refer (parse-int parse-float read-string)]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.i18n :refer (tr)]
   [uxbox.util.math :refer (precision-or-0)]))

(mf/defc size-options
  [{:keys [shape] :as props}]
  [:*
   [:span (tr "ds.size")]
   [:div.row-flex
    [:div.input-element.pixels
     [:input.input-text {:placeholder (tr "ds.width")
                         :type "number"
                         :min "0"
                         ;; :on-change #(on-size-change % shape :rx)
                         :value (precision-or-0 (:rx shape 0) 2)}]]
    [:div.lock-size {:class (when (:proportion-lock shape) "selected")
                     ;; :on-click #(on-proportion-lock-change % shape)
                     }
     (if (:proportion-lock shape) i/lock i/unlock)]

    [:div.input-element.pixels
     [:input.input-text
      {:placeholder (tr "ds.height")
       :type "number"
       :min "0"
       ;; :on-change #(on-size-change % shape :ry)
       :value (precision-or-0 (:ry shape 0) 2)}]]]])

(mf/defc position-options
  [{:keys [shape] :as props}]
  [:*
   [:span (tr "ds.position")]
   [:div.row-flex
    [:div.input-element.pixels
     [:input.input-text
      {:placeholder "cx"
       :type "number"
       ;; :on-change #(on-position-change % shape :x)
       :value (precision-or-0 (:cx shape 0) 2)}]]
    [:div.input-element.pixels
     [:input.input-text
      {:placeholder "cy"
       :type "number"
       ;; :on-change #(on-position-change % shape :y)
       :value (precision-or-0 (:cy shape 0) 2)}]]]])

(mf/defc rotation-options
  [{:keys [shape] :as props}]
  [:*
   [:span (tr "ds.rotation")]
   [:div.row-flex
    [:input.slidebar
     {:type "range"
      :min 0
      :max 360
      ;; :on-change #(on-rotation-change % shape)
      :value (:rotation shape 0)}]]

   [:div.row-flex
    [:div.input-element.degrees
     [:input.input-text
      {:placeholder ""
       :type "number"
       :min 0
       :max 360
       ;; :on-change #(on-rotation-change % shape)
       :value (precision-or-0 (:rotation shape 0) 2)}]]
    [:input.input-text
     {:style {:visibility "hidden"}}]]])

(mf/defc measures-options
  [{:keys [shape] :as props}]
  [:div.element-set
   [:div.element-set-title (tr "element.measures")]
   [:div.element-set-content
    [:& size-options {:shape shape}]
    [:& position-options {:shape shape}]
    [:& rotation-options {:shape shape}]]])

;; (defn- on-size-change
;;   [event shape attr]
;;   (let [value (dom/event->value event)
;;         value (parse-int value 0)
;;         sid (:id shape)
;;         props {attr value}]
;;     (st/emit! (udw/update-dimensions sid props))))

;; (defn- on-rotation-change
;;   [event shape]
;;   (let [value (dom/event->value event)
;;         value (parse-int value 0)
;;         sid (:id shape)]
;;     (st/emit! (udw/update-shape-attrs sid {:rotation value}))))

;; (defn- on-position-change
;;   [event shape attr]
;;   (let [value (dom/event->value event)
;;         value (parse-int value nil)
;;         sid (:id shape)
;;         point (gpt/point {attr value})]
;;     (st/emit! (udw/update-position sid point))))

;; (defn- on-proportion-lock-change
;;   [event shape]
;;   (if (:proportion-lock shape)
;;     (st/emit! (udw/unlock-proportions (:id shape)))
;;     (st/emit! (udw/lock-proportions (:id shape)))))


(mf/defc options
  [{:keys [shape] :as props}]
  [:div
   [:& measures-options {:shape shape}]
   [:& fill-menu {:shape shape}]
   [:& stroke-menu {:shape shape}]])
