
;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.border-radius
  (:require
   [app.common.types.token :as cto]
   [app.main.ui.workspace.tokens.management.forms.controls :as token.controls]
   [app.main.ui.workspace.tokens.management.forms.generic-form :as generic]
   [rumext.v2 :as mf]))


(mf/defc form*
  [{:keys [token token-type] :rest props}]
  (let [token
        (mf/with-memo [token]
          (if token
            (update token :value cto/join-font-family)
            {:type token-type}))
        props (mf/spread-props props {:token token
                                      :token-type token-type
                                      :input-component token.controls/combobox*})]
    [:> generic/form* props]))
