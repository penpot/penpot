;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2015-2017 Juan de la Cruz <delacruzgarciajuan@gmail.com>
;; Copyright (c) 2015-2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.main.ui.workspace.sidebar.options.stroke
  (:require
   [rumext.alpha :as mf]
   [uxbox.builtins.icons :as i]
   [uxbox.main.data.workspace :as udw]
   [uxbox.main.store :as st]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.colorpicker :refer [colorpicker-modal]]
   [uxbox.util.data :refer [parse-int parse-float read-string]]
   [uxbox.util.dom :as dom]
   [uxbox.util.i18n :refer [tr]]
   [uxbox.util.math :refer [precision-or-0]]))

(declare on-width-change)
(declare on-opacity-change)
(declare on-stroke-style-change)
(declare on-stroke-color-change)
(declare on-border-change)
(declare show-color-picker)

(mf/defc stroke-menu
  [{:keys [menu shape] :as props}]
  (let [local (mf/use-state {})
        on-border-lock #(swap! local update :border-lock not)
        on-stroke-style-change #(on-stroke-style-change % shape)
        on-width-change #(on-width-change % shape)
        on-stroke-color-change #(on-stroke-color-change % shape)
        on-border-change-rx #(on-border-change % shape local :rx)
        on-border-change-ry #(on-border-change % shape local :ry)
        on-opacity-change #(on-opacity-change % shape)
        show-color-picker #(show-color-picker % shape)]
    [:div.element-set
     [:div.element-set-title (:name menu)]
     [:div.element-set-content
      [:span (tr "ds.style")]
      [:div.row-flex
       [:select#style.input-select {:placeholder (tr "ds.style")
                                    :value (pr-str (:stroke-style shape))
                                    :on-change on-stroke-style-change}
        [:option {:value ":none"} (tr "ds.none")]
        [:option {:value ":solid"} (tr "ds.solid")]
        [:option {:value ":dotted"} (tr "ds.dotted")]
        [:option {:value ":dashed"} (tr "ds.dashed")]
        [:option {:value ":mixed"} (tr "ds.mixed")]]
       [:div.input-element.pixels
        [:input.input-text
         {:placeholder (tr "ds.width")
          :type "number"
          :min "0"
          :value (precision-or-0 (:stroke-width shape 1) 2)
          :on-change on-width-change}]]]

      [:span (tr "ds.color")]
      [:div.row-flex.color-data
       [:span.color-th
        {:style {:background-color (:stroke-color shape)}
         :on-click show-color-picker}]
       [:div.color-info
        [:input
         {:on-change on-stroke-color-change
          :value (:stroke-color shape "")}]]]

      [:span (tr "ds.radius")]
      [:div.row-flex
       [:div.input-element.pixels
        [:input.input-text
         {:placeholder "rx"
          :type "number"
          :value (precision-or-0 (:rx shape 0) 2)
          :on-change on-border-change-rx}]]
       [:div.lock-size
        {:class (when (:border-lock @local) "selected")
         :on-click on-border-lock}
        i/lock]
       [:div.input-element.pixels
        [:input.input-text
         {:placeholder "ry"
          :type "number"
          :value (precision-or-0 (:ry shape 0) 2)
          :on-change on-border-change-ry}]]]

      [:span (tr "ds.opacity")]
      [:div.row-flex
       [:input.slidebar
        {:type "range"
         :min "0"
         :max "10000"
         :value (* 10000 (:stroke-opacity shape 1))
         :step "1"
         :on-change on-opacity-change}]]]]))

(defn- on-width-change
  [event shape]
  (let [value (-> (dom/event->value event)
                  (parse-float 1))]
    (st/emit! (udw/update-shape-attrs (:id shape) {:stroke-width value}))))

(defn- on-opacity-change
  [event shape]
  (let [value (-> (dom/event->value event)
                  (parse-float 1)
                  (/ 10000))]
    (st/emit! (udw/update-shape-attrs (:id shape) {:stroke-opacity value}))))

(defn- on-stroke-style-change
  [event shape]
  (let [value (-> (dom/event->value event)
                  (read-string))]
    (st/emit! (udw/update-shape-attrs (:id shape) {:stroke-style value}))))

(defn- on-stroke-color-change
  [event shape]
  (let [value (dom/event->value event)]
    (st/emit! (udw/update-shape-attrs (:id shape) {:stroke-color value}))))

(defn- on-border-change
  [event shape local attr]
  (let [value (-> (dom/event->value event)
                  (parse-int nil))
        id (:id shape)]
    (if (:border-lock @local)
      (st/emit! (udw/update-shape-attrs id {:rx value :ry value}))
      (st/emit! (udw/update-shape-attrs id {attr value})))))

(defn- show-color-picker
  [event shape]
  (let [x (.-clientX event)
        y (.-clientY event)
        props {:x x :y y
               :default "#ffffff"
               :value (:stroke-color shape)
               :on-change #(st/emit! (udw/update-shape-attrs (:id shape) {:stroke-color %}))
               :transparent? true}]
    (modal/show! colorpicker-modal props)))
