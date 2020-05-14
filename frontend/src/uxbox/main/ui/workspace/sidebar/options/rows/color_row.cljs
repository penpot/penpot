;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.rows.color-row
  (:require
   [rumext.alpha :as mf]
   [uxbox.util.math :as math]
   [uxbox.util.dom :as dom]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.colorpicker :refer [colorpicker-modal]]
   [uxbox.common.data :as d]))

(defn color-picker-callback [color handle-change-color]
  (fn [event]
    (let [x (.-clientX event)
          y (.-clientY event)
          props {:x x
                 :y y
                 :on-change handle-change-color
                 :value (:value color)
                 :transparent? true}]
      (modal/show! colorpicker-modal props))))

(defn opacity->string [opacity]
  (str (-> opacity
           (d/coalesce 1)
           (* 100)
           (math/round))))

(defn string->opacity [opacity-str]
  (-> opacity-str
      (d/parse-integer 1)
      (/ 100)))

(mf/defc color-row [{:keys [value on-change]}]
  (let [state (mf/use-state value)
        change-color (fn [color]
                       (let [update-color (fn [state] (assoc state :value color))]
                         (swap! state update-color)
                         (when on-change (on-change (update-color @state)))))

        change-opacity (fn [opacity]
                         (let [update-opacity (fn [state] (assoc state :opacity opacity))]
                           (swap! state update-opacity)
                           (when on-change (on-change (update-opacity @state)))))

        handle-pick-color (fn [color]
                            (change-color color))

        handle-input-color-change (fn [event]
                                    (let [target (dom/get-target event)
                                          value (dom/get-value target)]
                                      (when (dom/valid? target)
                                        (change-color value))))
        handle-opacity-change (fn [event]
                                (-> event
                                    dom/get-target
                                    dom/get-value
                                    string->opacity
                                    change-opacity))]

    [:div.row-flex.color-data
     [:span.color-th
      {:style {:background-color (-> @state :value)}
       :on-click (color-picker-callback @state handle-pick-color)}]

     [:div.color-info
      [:input {:value (-> @state :value)
               :pattern "^#(?:[0-9a-fA-F]{3}){1,2}$"
               :on-change handle-input-color-change}]]

     [:div.input-element.percentail
      [:input.input-text {:type "number"
                          :value (-> @state :opacity opacity->string)
                          :on-change handle-opacity-change
                          :min "0"
                          :max "100"}]]

     [:input.slidebar {:type "range"
                       :min "0"
                       :max "100"
                       :value (-> @state :opacity opacity->string)
                       :step "1"
                       :on-change handle-opacity-change}]]))
