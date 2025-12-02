;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.select-token
  (:require
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.forms :as fc]
   [rumext.v2 :as mf]))

(mf/defc select-composite*
  [{:keys [name index composite-type] :rest props}]
  (let [form       (mf/use-ctx fc/context)
        input-name name

        value
        (get-in @form [:data :value composite-type index input-name] false)

        on-change
        (mf/use-fn
         (mf/deps input-name)
         (fn [type]
           (let [is-inner? (= type "inner")]
             (swap! form assoc-in [:data :value composite-type index input-name] is-inner?))))

        props (mf/spread-props props {:default-selected (if value "inner" "drop")
                                      :variant "ghost"
                                      :on-change on-change})]
    [:> select* props]))