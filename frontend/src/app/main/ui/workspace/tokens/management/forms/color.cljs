;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.color
  (:require
   [app.common.files.tokens :as cfo]
   [app.common.schema :as sm]
   [app.main.ui.workspace.tokens.management.forms.controls :as token.controls]
   [app.main.ui.workspace.tokens.management.forms.generic-form :as generic]
   [rumext.v2 :as mf]))

(mf/defc form*
  [{:keys [token token-type] :as props}]
  (let [props (mf/spread-props props {:make-schema #(-> (cfo/make-token-schema %1 token-type)
                                                        (sm/dissoc-key :id)
                                                        (sm/assoc-key :color-result :string))
                                      :initial {:type token-type
                                                :name (:name token "")
                                                :value (:value token "")
                                                :description (:description token "")
                                                :color-result ""}
                                      :input-component token.controls/color-input*})]
    [:> generic/form* props]))
