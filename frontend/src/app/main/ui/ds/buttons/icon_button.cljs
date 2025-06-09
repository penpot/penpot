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
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [app.main.ui.ds.tooltip.tooltip :refer [tooltip*]]
   [rumext.v2 :as mf]))

(def ^:private schema:icon-button
  [:map
   [:class {:optional true} :string]
   [:icon-class {:optional true} :string]
   [:icon
    [:and :string [:fn #(contains? icon-list %)]]]
   [:aria-label :string]
   [:variant {:optional true}
    [:maybe [:enum "primary" "secondary" "ghost" "destructive" "action"]]]])

(mf/defc icon-button*
  {::mf/schema schema:icon-button
   ::mf/memo true}
  [{:keys [class icon icon-class variant aria-label children] :rest props}]
  (let [variant
        (d/nilv variant "primary")

        tooltip-id
        (mf/use-id)

        class
        (dm/str class " "
                (stl/css-case :icon-button true
                              :icon-button-primary (= variant "primary")
                              :icon-button-secondary (= variant "secondary")
                              :icon-button-ghost (= variant "ghost")
                              :icon-button-action (= variant "action")
                              :icon-button-destructive (= variant "destructive")))

        props
        (mf/spread-props props
                         {:class class
                          :aria-labelledby tooltip-id})]

    [:> tooltip* {:tooltip-content aria-label
                  :id tooltip-id}
     [:> "button" props
      [:> icon* {:icon-id icon :aria-hidden true :class icon-class}]
      children]]))
