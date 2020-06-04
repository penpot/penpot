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
   [uxbox.common.math :as math]
   [uxbox.util.dom :as dom]
   [uxbox.main.ui.modal :as modal]
   [uxbox.main.ui.workspace.colorpicker :refer [colorpicker-modal]]
   [uxbox.common.data :as d]))

(defn color-picker-callback [color handle-change-color disable-opacity]
  (fn [event]
    (let [x (.-clientX event)
          y (.-clientY event)
          props {:x x
                 :y y
                 :on-change handle-change-color
                 :value (:value color)
                 :opacity (:opacity color)
                 :transparent? true
                 :disable-opacity disable-opacity}]
      (modal/show! colorpicker-modal props))))

(defn opacity->string [opacity]
  (if (and opacity (not= opacity ""))
    (str (-> opacity
             (d/coalesce 1)
             (* 100)
             (math/round)))
    ""))

(defn string->opacity [opacity-str]
  (when (and opacity-str (not= "" opacity-str))
    (-> opacity-str
        (d/parse-integer 1)
        (/ 100))))

(mf/defc color-row [{:keys [value on-change disable-opacity]}]
  (let [default-value {:value "#000000" :opacity 1}

        parse-value (fn [value]
                      (-> (merge default-value value)
                          (update :value #(or % "#000000"))
                          (update :opacity #(or % 1))))

        state (mf/use-state (parse-value value))

        change-color (fn [new-value]
                       (let [{:keys [value opacity]} @state]
                         (swap! state assoc :value new-value)
                         (when on-change (on-change new-value opacity))))

        change-opacity (fn [new-opacity]
                         (let [{:keys [value opacity]} @state]
                           (swap! state assoc :opacity new-opacity)
                           (when (and new-opacity on-change) (on-change value new-opacity))))

        handle-pick-color (fn [color opacity]
                            (reset! state {:value color :opacity opacity})
                            (when on-change (on-change color opacity)))

        handle-input-color-change (fn [event]
                                    (let [target (dom/get-target event)
                                          value (dom/get-value target)]
                                      (when (dom/valid? target)
                                        (change-color (str "#" value)))))
        handle-opacity-change (fn [event]
                                (-> event
                                    dom/get-target
                                    dom/get-value
                                    string->opacity
                                    change-opacity))
        select-all #(-> % (dom/get-target) (.select))]

    (mf/use-effect
     (mf/deps value)
     #(reset! state (parse-value value)))

    [:div.row-flex.color-data
     [:span.color-th
      {:style {:background-color (-> @state :value)}
       :on-click (color-picker-callback @state handle-pick-color disable-opacity)}]

     [:div.color-info
      [:input {:value (-> @state :value (subs 1))
               :pattern "^[0-9a-fA-F]{0,6}$"
               :on-click select-all
               :on-change handle-input-color-change}]]

     (when (not disable-opacity)
       [:div.input-element.percentail
        [:input.input-text {:type "number"
                            :value (-> @state :opacity opacity->string)
                            :on-click select-all
                            :on-change handle-opacity-change
                            :min "0"
                            :max "100"}]])

     #_[:input.slidebar {:type "range"
                       :min "0"
                       :max "100"
                       :value (-> @state :opacity opacity->string)
                       :step "1"
                       :on-change handle-opacity-change}]]))

