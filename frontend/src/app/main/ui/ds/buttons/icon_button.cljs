;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.buttons.icon-button
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [rumext.v2 :as mf]))

(def ^:private schema:icon-button
  [:map
   [:class {:optional true} :string]
   [:icon-class {:optional true} :string]
   [:tooltip-id {:optional true} :string]
   [:icon
    [:and :string [:fn #(contains? icon-list %)]]]
   [:aria-label :string]
   [:variant {:optional true}
    [:maybe [:enum "primary" "secondary" "ghost" "destructive" "action"]]]])

(mf/defc icon-button*
  {::mf/props :obj
   ::mf/schema schema:icon-button}
  [{:keys [class icon icon-class variant aria-label children tooltip-id] :rest props}]
  (let [variant (or variant "primary")
        class (dm/str class " " (stl/css-case :icon-button true
                                              :icon-button-primary (= variant "primary")
                                              :icon-button-secondary (= variant "secondary")
                                              :icon-button-ghost (= variant "ghost")
                                              :icon-button-action (= variant "action")
                                              :icon-button-destructive (= variant "destructive")))
        props (if (some? tooltip-id)
                (mf/spread-props props {:class class
                                        :aria-describedby tooltip-id})
                (mf/spread-props props {:class class
                                        :aria-label aria-label
                                        :title aria-label}))]
    [:> "button" props [:> icon* {:icon-id icon :aria-hidden true :class icon-class}] children]))