;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.radio-buttons
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.common.math :as math]
   [app.main.ui.formats :as fmt]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(def ctx-radio-button (mf/create-context nil))

(mf/defc radio-button
  {::mf/wrap-props false}
  [props]
  (let [ctx (mf/use-ctx ctx-radio-button)
        icon  (unchecked-get props "icon")
        id (unchecked-get props "id")
        on-change (:on-change ctx)
        selected (:selected ctx)
        value (unchecked-get props "value")
        checked? (= selected value)
        name (:name ctx)]
    [:label {:for id
             :class (dom/classnames (css :radio-icon) true
                                    (css :checked) checked?)}
     icon
     [:input {:id id
              :on-change on-change
              :type "radio"
              :name name
              :value value
              :checked  checked?}]]))

(mf/defc nilable-option
  {::mf/wrap-props false}
  [props]
  (let [ctx (mf/use-ctx ctx-radio-button)
        icon  (unchecked-get props "icon")
        id (unchecked-get props "id")
        on-change (:on-change ctx)
        selected (:selected ctx)
        value (unchecked-get props "value")
        checked? (= selected value)
        name (:name ctx)]
    [:label {:for id
             :class (dom/classnames (css :radio-icon) true
                                    (css :checked) checked?)}
     icon
     [:input {:id id
              :on-change on-change
              :type "checkbox"
              :name name
              :value value
              :checked  checked?}]]))

(mf/defc radio-buttons
  {::mf/wrap-props false}
  [props]
  (let [children  (unchecked-get props "children")
        on-change (unchecked-get props "on-change")
        selected  (unchecked-get props "selected")
        name      (unchecked-get props "name")
        calculate-width (fmt/format-pixels (+ (math/pow 2 (count children)) (* 28 (count children))))
        handle-change
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (let [value (dom/get-target-val event)]
             (on-change value event))))]
    [:& (mf/provider ctx-radio-button) {:value {:selected selected :on-change handle-change :name name}}
     [:div {:class (css :radio-btn-wrapper)
                          :style {:width calculate-width}}
      children]]))
