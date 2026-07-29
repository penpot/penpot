;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.tokens.management.forms.form-container
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.config :as cf]
   [app.main.refs :as refs]
   [app.main.ui.workspace.tokens.management.forms.color :as color]
   [app.main.ui.workspace.tokens.management.forms.controls :as token.controls]
   [app.main.ui.workspace.tokens.management.forms.font-family :as font-family]
   [app.main.ui.workspace.tokens.management.forms.generic-form :as generic]
   [app.main.ui.workspace.tokens.management.forms.shadow :as shadow]
   [app.main.ui.workspace.tokens.management.forms.typography :as typography]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc form-container*
  [{:keys [token token-type initial-errors] :rest props}]
  (let [token-type
        (or (:type token) token-type)

        selected-token-set-id
        (mf/deref refs/selected-token-set-id)

        tokens-in-selected-set
        (mf/deref refs/workspace-all-tokens-in-selected-set)

        token-path
        (mf/with-memo [token]
          (ctob/get-token-path token))

        tokens-tree-in-selected-set
        (mf/with-memo [tokens-in-selected-set]
          (ctob/tokens-tree tokens-in-selected-set))

        props
        (mf/spread-props props {:token-type token-type
                                :initial-errors initial-errors
                                :tokens-tree-in-selected-set tokens-tree-in-selected-set
                                :selected-token-set-id selected-token-set-id
                                :current-token-path token-path
                                :token token})

        props
        (if (contains? cf/flags :token-combobox)
          (mf/spread-props props {:input-component token.controls/value-combobox*})
          props)

        text-case-props
        (mf/spread-props props {:input-value-placeholder (tr "workspace.tokens.text-case-value-enter")})

        text-decoration-props
        (mf/spread-props props {:input-value-placeholder (tr "workspace.tokens.text-decoration-value-enter")})

        font-weight-props
        (mf/spread-props props {:input-value-placeholder (tr "workspace.tokens.font-weight-value-enter")})]

    (case token-type
      :color [:> color/form* props]
      :typography [:> typography/form* props]
      :shadow [:> shadow/form* props]
      :font-family [:> font-family/form* props]
      :text-case [:> generic/form* text-case-props]
      :text-decoration [:> generic/form* text-decoration-props]
      :font-weight [:> generic/form* font-weight-props]
      [:> generic/form* props])))
