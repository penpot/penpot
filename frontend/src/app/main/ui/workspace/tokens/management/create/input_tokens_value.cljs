;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.input-tokens-value
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.ds.controls.utilities.input-field :refer [input-field*]]
   [app.main.ui.ds.controls.utilities.label :refer [label*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon-list]]
   [rumext.v2 :as mf]))

(def ^:private schema::input-tokens-value
  [:map
   [:label :string]
   [:placeholder {:optional true} :string]
   [:value {:optional true} [:maybe :string]]
   [:class {:optional true} :string]
   [:error {:optional true} :boolean]
   [:slot-start {:optional true} [:maybe some?]]
   [:icon {:optional true}
    [:maybe [:and :string [:fn #(contains? icon-list %)]]]]])

(mf/defc input-tokens-value*
  {::mf/props :obj
   ::mf/forward-ref true
   ::mf/schema schema::input-tokens-value}
  [{:keys [class label placeholder error value icon slot-start] :rest props} ref]
  (let [id (mf/use-id)
        input-ref (mf/use-ref)
        props (mf/spread-props props {:id id
                                      :type "text"
                                      :class (stl/css :input)
                                      :placeholder placeholder
                                      :value value
                                      :variant "comfortable"
                                      :hint-type (when error "error")
                                      :slot-start slot-start
                                      :icon icon
                                      :ref (or ref input-ref)})]
    [:div {:class (dm/str class " " (stl/css-case :wrapper true
                                                  :input-error error))}
     [:> label* {:for id} label]
     [:> input-field* props]]))
