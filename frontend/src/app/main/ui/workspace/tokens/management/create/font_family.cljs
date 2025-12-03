;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.font-family
  (:require
   [app.common.types.token :as cto]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.ui.workspace.tokens.management.create.combobox-token-fonts :refer [font-picker-combobox*]]
   [app.main.ui.workspace.tokens.management.create.form :as form]
   [app.main.ui.workspace.tokens.management.create.token-form-validators :refer [check-coll-self-reference default-validate-token]]
   [rumext.v2 :as mf]))

(defn- check-font-family-token-self-reference [token]
  (check-coll-self-reference (:name token) (:value token)))

(defn- validate-font-family-token
  [props]
  (-> props
      (update :token-value cto/split-font-family)
      (assoc :validators [(fn [token]
                            (when (empty? (:value token))
                              (wte/get-error-code :error.token/empty-input)))
                          check-font-family-token-self-reference])
      (default-validate-token)))

(mf/defc form*
  [{:keys [token token-type] :rest props}]
  (let [token
        (mf/with-memo [token]
          (if token
            (update token :value cto/join-font-family)
            {:type token-type}))
        props (mf/spread-props props {:token token
                                      :token-type token-type
                                      :validate-token validate-font-family-token
                                      :input-token-component font-picker-combobox*})]
    [:> form/form* props]))
