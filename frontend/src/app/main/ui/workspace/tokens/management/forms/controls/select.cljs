;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.controls.select
  (:require
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.forms :as fc]
   [rumext.v2 :as mf]))

;; --- Select Input (Indexed) --------------------------------------------------
;;
;; This input type is part of the indexed system, used for fields that exist
;; inside an array of maps stored in a value-subfield of :value.
;;
;; - Writes to a nested location:
;;       [:value <value-subfield> <index> <field>]
;; - Each item in the array has its own select input, independent of others.
;; - Validation ensures the selected value is valid for that field.
;; - Changing one item does not affect the other items in the array.
;; - Ideal for properties with predefined options (boolean, enum, etc.) where
;;   multiple instances exist.


(mf/defc select-indexed*
  [{:keys [name index indexed-type] :rest props}]
  (let [form       (mf/use-ctx fc/context)
        input-name name

        value
        (get-in @form [:data :value indexed-type index input-name] false)

        on-change
        (mf/use-fn
         (mf/deps input-name)
         (fn [id]
           (let [is-inner? (= id "inner")]
             (swap! form assoc-in [:data :value indexed-type index input-name] is-inner?))))

        props (mf/spread-props props {:default-selected (if value "inner" "drop")
                                      :variant "ghost"
                                      :on-change on-change})]
    [:> select* props]))