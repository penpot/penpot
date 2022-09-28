;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.rows.input-row
  (:require
   [app.main.ui.components.editable-select :refer [editable-select]]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.components.select :refer [select]]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(mf/defc input-row [{:keys [label options value class min max on-change type placeholder default nillable]}]
  [:div.row-flex.input-row
   [:span.element-set-subtitle label]
   [:div.input-element {:class class}

    (case type
      :select
      [:& select {:default-value value
                  :class "input-option"
                  :options options
                  :on-change on-change}]

      :editable-select
      [:& editable-select {:value value
                           :class "input-option"
                           :options options
                           :type "number"
                           :min min
                           :max max
                           :placeholder placeholder
                           :on-change on-change}]

      :text
      [:input {:value value
               :class "input-text"
               :on-change on-change} ]

      [:> numeric-input
       {:placeholder placeholder
        :min min
        :max max
        :default default
        :nillable nillable
        :on-change on-change
        :value (or value "")}])]])


;; NOTE: (by niwinz) this is a new version of input-row, I didn't
;; touched the original one because it is used in many sites and I
;; don't have intention to refactor all the code right now. We should
;; consider to use the new one and once we have migrated all to the
;; new component, we can proceed to rename it and delete the old one.

(mf/defc input-row-v2
  {::mf/wrap-props false}
  [props]
  (let [label    (obj/get props "label")
        class    (obj/get props "class")
        children (obj/get props "children")]
    [:div.row-flex.input-row
     [:span.element-set-subtitle label]
     [:div.input-element {:class class}
      children]]))

