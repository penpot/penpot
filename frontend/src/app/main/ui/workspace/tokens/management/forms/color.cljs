;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.color
  (:require
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.ui.workspace.tokens.management.forms.controls :as token.controls]
   [app.main.ui.workspace.tokens.management.forms.generic-form :as generic]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- token-value-error-fn
  [{:keys [value]}]
  (when (or (str/empty? value)
            (str/blank? value))
    (tr "workspace.tokens.empty-input")))

(defn- make-schema
  [tokens-tree _]
  (sm/schema
   [:and
    [:map
     [:name
      [:and
       [:string {:min 1 :max 255 :error/fn #(str (:value %) (tr "workspace.tokens.token-name-length-validation-error"))}]
       (sm/update-properties cto/schema:token-name assoc :error/fn #(str (:value %) (tr "workspace.tokens.token-name-validation-error")))
       [:fn {:error/fn #(tr "workspace.tokens.token-name-duplication-validation-error" (:value %))}
        #(not (ctob/token-name-path-exists? % tokens-tree))]]]

     [:value [::sm/text {:error/fn token-value-error-fn}]]

     [:color-result {:optional true} ::sm/any]

     [:description {:optional true}
      [:string {:max 2048 :error/fn #(tr "errors.field-max-length" 2048)}]]]

    [:fn {:error/field :value
          :error/fn #(tr "workspace.tokens.self-reference")}
     (fn [{:keys [name value]}]
       (when (and name value)
         (nil? (cto/token-value-self-reference? name value))))]]))

(mf/defc form*
  [props]
  (let [props (mf/spread-props props {:make-schema make-schema
                                      :input-component token.controls/color-input*})]
    [:> generic/form* props]))
