;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.rows.input-row
  (:require
   [rumext.alpha :as mf]
   [app.common.data :as d]
   [app.main.ui.components.numeric-input :refer [numeric-input]]
   [app.main.ui.components.select :refer [select]]
   [app.main.ui.components.editable-select :refer [editable-select]]
   [app.util.dom :as dom]))

(mf/defc input-row [{:keys [label options value class min max on-change type placeholder]}]
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
                           :type (when (number? value) "number")
                           :placeholder placeholder
                           :on-change on-change}]

      :text
      [:input {:value value
               :class "input-text"
               :on-change on-change} ]

      [:> numeric-input {:placeholder placeholder
                         :min min
                         :max max
                         :on-change on-change
                         :value (or value "")}])]])

