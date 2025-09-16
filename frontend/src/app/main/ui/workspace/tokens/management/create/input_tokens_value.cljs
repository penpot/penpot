;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.input-tokens-value
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.workspace.tokens.errors :as wte]
   [app.main.data.workspace.tokens.format :as dwtf]
   [app.main.data.workspace.tokens.warnings :as wtw]
   [app.main.ui.ds.controls.utilities.hint-message :refer [hint-message*]]
   [app.main.ui.ds.controls.utilities.input-field :refer [input-field*]]
   [app.main.ui.ds.controls.utilities.label :refer [label*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon-list]]
   [app.util.i18n :refer [tr]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema::input-token
  [:map
   [:label {:optional true} [:maybe :string]]
   [:aria-label {:optional true} [:maybe :string]]
   [:placeholder {:optional true} :string]
   [:value {:optional true} [:maybe :string]]
   [:class {:optional true} :string]
   [:error {:optional true} :boolean]
   [:slot-start {:optional true} [:maybe some?]]
   [:icon {:optional true}
    [:maybe [:and :string [:fn #(contains? icon-list %)]]]]
   [:token-resolve-result {:optional true} :any]])

(mf/defc token-value-hint*
  [{:keys [result]}]
  (let [{:keys [errors warnings resolved-value]} result
        empty-message? (nil? result)

        message (cond
                  empty-message? (tr "workspace.tokens.resolved-value" "-")
                  warnings (->> (wtw/humanize-warnings warnings)
                                (str/join "\n"))
                  errors (->> (wte/humanize-errors errors)
                              (str/join "\n"))
                  :else (tr "workspace.tokens.resolved-value" (dwtf/format-token-value (or resolved-value result))))
        type (cond
               empty-message? "hint"
               errors "error"
               warnings "warning"
               :else "hint")]
    [:> hint-message*
     {:id "token-value-hint"
      :message message
      :class (stl/css-case :resolved-value (not (or empty-message? (seq warnings) (seq errors))))
      :type type}]))

(mf/defc input-token*
  {::mf/forward-ref true
   ::mf/schema schema::input-token}
  [{:keys [class label token-resolve-result] :rest props} ref]
  (let [error (not (nil? (:errors token-resolve-result)))
        id (mf/use-id)
        input-ref (mf/use-ref)
        props (mf/spread-props props {:id id
                                      :type "text"
                                      :class (stl/css :input)
                                      :variant "comfortable"
                                      :hint-type (when error "error")
                                      :ref (or ref input-ref)})]
    [:*
     [:div {:class (dm/str class " " (stl/css-case :wrapper true
                                                   :input-error error))}
      (when label
        [:> label* {:for id} label])
      [:> input-field* props]]
     (when token-resolve-result
       [:> token-value-hint* {:result token-resolve-result}])]))
