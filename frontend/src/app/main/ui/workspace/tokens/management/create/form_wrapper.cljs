;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.form-wrapper
  (:require
   [app.common.data :as d]
   [app.common.files.tokens :as cft]
   [app.common.types.tokens-lib :as ctob]
   [app.main.refs :as refs]
   [app.main.ui.workspace.tokens.management.create.color :as color]
   [app.main.ui.workspace.tokens.management.create.font-family :as font-family]
   [app.main.ui.workspace.tokens.management.create.form :refer [form*]]
   [app.main.ui.workspace.tokens.management.create.shadow :as shadow]
   [app.main.ui.workspace.tokens.management.create.typography :as typography]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc form-wrapper*
  [{:keys [token token-type] :rest props}]
  (let [token-type
        (or (:type token) token-type)

        tokens-in-selected-set
        (mf/deref refs/workspace-all-tokens-in-selected-set)

        token-path
        (mf/with-memo [token]
          (cft/token-name->path (:name token)))

        tokens-tree-in-selected-set
        (mf/with-memo [token-path tokens-in-selected-set]
          (-> (ctob/tokens-tree tokens-in-selected-set)
              (d/dissoc-in token-path)))
        props
        (mf/spread-props props {:token-type token-type
                                :tokens-tree-in-selected-set tokens-tree-in-selected-set
                                :token token})
        text-case-props (mf/spread-props props {:input-value-placeholder (tr "workspace.tokens.text-case-value-enter")})
        text-decoration-props (mf/spread-props props {:input-value-placeholder (tr "workspace.tokens.text-decoration-value-enter")})
        font-weight-props (mf/spread-props props {:input-value-placeholder (tr "workspace.tokens.font-weight-value-enter")})]

    (case token-type
      :color [:> color/form* props]
      :typography [:> typography/form* props]
      :shadow [:> shadow/form* props]
      :font-family [:> font-family/form* props]
      :text-case [:> form* text-case-props]
      :text-decoration [:> form* text-decoration-props]
      :font-weight [:> form* font-weight-props]
      [:> form* props])))