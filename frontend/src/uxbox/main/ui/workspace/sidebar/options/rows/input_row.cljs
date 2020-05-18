;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns uxbox.main.ui.workspace.sidebar.options.rows.input-row
  (:require
   [rumext.alpha :as mf]
   [uxbox.common.data :as d]
   [uxbox.main.ui.components.select :refer [select]]
   [uxbox.util.dom :as dom]))

(mf/defc input-row [{:keys [label options value class min max on-change]}]
  (let [handle-change (fn [value] (when (and (or (not min) (>= value min)) (or (not max) (<= value max)))
                                    (on-change value)))]
    [:div.row-flex.input-row
     [:span.element-set-subtitle label]
     [:div.input-element {:class class}
      (if options
        [:& select {:default-value value
                    :class "input-option"
                    :options options
                    :on-change on-change}]
        [:input.input-text
         {:placeholder label
          :type "number"
          :on-change #(-> % dom/get-target dom/get-value d/parse-integer handle-change)
          :value value}])]]))
