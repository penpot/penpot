;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.sidebar.options.circle-measures
  (:require
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.shapes :as uds]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.geom :as geom]
   [uxbox.main.store :as st]
   [uxbox.util.data :refer (parse-int parse-float read-string)]
   [uxbox.util.dom :as dom]
   [uxbox.util.geom.point :as gpt]
   [uxbox.util.i18n :refer (tr)]
   [uxbox.util.math :refer (precision-or-0)]))

(declare on-size-change)
(declare on-rotation-change)
(declare on-position-change)
(declare on-proportion-lock-change)

(mf/defc circle-measures-menu
  [{:keys [menu shape] :as props}]
  [:div.element-set {:key (str (:id menu))}
   [:div.element-set-title (:name menu)]
   [:div.element-set-content
    ;; SLIDEBAR FOR ROTATION AND OPACITY
    [:span "Size"]
    [:div.row-flex
     [:div.input-element.pixels
      [:input.input-text
       {:placeholder "Width"
        :type "number"
        :min "0"
        :value (precision-or-0 (:rx shape 0) 2)
        :on-change #(on-size-change % shape :rx)}]]
     [:div.lock-size {:class (when (:proportion-lock shape) "selected")
                      :on-click #(on-proportion-lock-change % shape)}
      (if (:proportion-lock shape) i/lock i/unlock)]

     [:div.input-element.pixels
      [:input.input-text
       {:placeholder "Height"
        :type "number"
        :min "0"
        :value (precision-or-0 (:ry shape 0) 2)
        :on-change #(on-size-change % shape :ry)}]]]

    [:span "Position"]
    [:div.row-flex
     [:div.input-element.pixels
      [:input.input-text
       {:placeholder "cx"
        :type "number"
        :value (precision-or-0 (:cx shape 0) 2)
        :on-change #(on-position-change % shape :x)}]]
     [:div.input-element.pixels
      [:input.input-text
       {:placeholder "cy"
        :type "number"
        :value (precision-or-0 (:cy shape 0) 2)
        :on-change #(on-position-change % shape :y)}]]]

    [:span "Rotation"]
    [:div.row-flex
     [:input.slidebar
      {:type "range"
       :min 0
       :max 360
       :value (:rotation shape 0)
       :on-change #(on-rotation-change % shape)}]]

    [:div.row-flex
     [:div.input-element.degrees
      [:input.input-text
       {:placeholder ""
        :type "number"
        :min 0
        :max 360
        :value (precision-or-0 (:rotation shape 0) 2)
        :on-change #(on-rotation-change % shape)}]]
     [:input.input-text
      {:style {:visibility "hidden"}}]]]])

(defn- on-size-change
  [event shape attr]
  (let [value (dom/event->value event)
        value (parse-int value 0)
        sid (:id shape)
        props {attr value}]
    (st/emit! (udw/update-dimensions sid props))))

(defn- on-rotation-change
  [event shape]
  (let [value (dom/event->value event)
        value (parse-int value 0)
        sid (:id shape)]
    (st/emit! (udw/update-shape-attrs sid {:rotation value}))))

(defn- on-position-change
  [event shape attr]
  (let [value (dom/event->value event)
        value (parse-int value nil)
        sid (:id shape)
        point (gpt/point {attr value})]
    (st/emit! (udw/update-position sid point))))

(defn- on-proportion-lock-change
  [event shape]
  (if (:proportion-lock shape)
    (st/emit! (udw/unlock-proportions (:id shape)))
    (st/emit! (udw/lock-proportions (:id shape)))))

