;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.buttons.icon-button
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [rumext.v2 :as mf]))

(def ^:private schema:icon-button
  [:map
   [:class {:optional true} :string]
   [:icon-class {:optional true} :string]
   [:icon
    [:and :string [:fn #(contains? icon-list %)]]]
   [:aria-label :string]
   [:tooltip-placement {:optional true}
    [:maybe [:enum "top" "bottom" "left" "right" "top-right" "bottom-right" "bottom-left" "top-left"]]]
   [:variant {:optional true}
    [:maybe [:enum "primary" "secondary" "ghost" "destructive" "action"]]]])

(mf/defc icon-button*
  {::mf/schema schema:icon-button
   ::mf/memo true}
  [{:keys [class icon icon-class variant aria-label children tooltip-placement] :rest props}]
  (let [variant
        (d/nilv variant "primary")

        tooltip-id
        (mf/use-id)

        button-class
        (stl/css-case :icon-button true
                      :icon-button-primary (identical? variant "primary")
                      :icon-button-secondary (identical? variant "secondary")
                      :icon-button-ghost (identical? variant "ghost")
                      :icon-button-action (identical? variant "action")
                      :icon-button-destructive (identical? variant "destructive"))

        props
        (mf/spread-props props
                         {:class [class button-class]
                          :aria-labelledby tooltip-id})]

    [:> tooltip* {:content aria-label
                  :placement tooltip-placement
                  :id tooltip-id}
     [:> :button props
      [:> icon* {:icon-id icon :aria-hidden true :class icon-class}]
      children]]))
