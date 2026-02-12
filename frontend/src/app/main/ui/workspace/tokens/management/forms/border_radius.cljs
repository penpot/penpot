;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.border-radius
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cft]
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.main.ui.workspace.tokens.management.forms.generic-form :as generic]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(defn- make-schema
  [tokens-tree]
  (sm/schema
   [:and
    [:map
     [:name
      [:and
       [:string {:min 1 :max 255
                 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
       (sm/update-properties cto/token-name-ref assoc
                             :error/fn #(str (:value %) (tr "workspace.tokens.token-name-validation-error")))
       [:fn {:error/fn #(tr "workspace.tokens.token-name-duplication-validation-error" (:value %))}
        #(not (cft/token-name-path-exists? % tokens-tree))]]]

     [:value
      [:and
       [::sm/text]
       [:fn {:error/fn #(tr "workspace.tokens.border-radius-token-value-error")}
        (fn [value]
          (let [n (d/parse-double value)]
            (or (nil? n) (not (< n 0)))))]]]

     [:description {:optional true}
      [:string {:max 2048 :error/fn #(tr "errors.field-max-length" 2048)}]]]]))


(mf/defc form*
  [{:keys [token token-type] :rest props}]
  (let [props (mf/spread-props props {:token token
                                      :make-schema make-schema
                                      :token-type token-type})]
    [:> generic/form* props]))
