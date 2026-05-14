;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

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
   [:class {:optional true} [:maybe :string]]
   [:tooltip-class {:optional true} [:maybe :string]]
   [:type {:optional true} [:maybe [:enum "button" "submit" "reset"]]]
   [:icon-class {:optional true} [:maybe :string]]
   [:icon-size {:optional true} [:maybe [:enum "s" "m" "l"]]]
   [:icon
    [:and :string [:fn #(contains? icon-list %)]]]
   [:aria-label :string]
   [:has-tooltip {:optional true} [:maybe :boolean]]
   [:tooltip-placement {:optional true}
    [:maybe [:enum "top" "bottom" "left" "right" "top-right" "bottom-right" "bottom-left" "top-left"]]]
   ;; Indicates that the button has a flyout menu, and should display an indicator
   [:flyout-indicator {:optional true} [:maybe :boolean]]
   [:variant {:optional true}
    [:maybe [:enum "primary" "secondary" "ghost" "destructive" "action"]]]])

(def ^:private schema:icon-button-internal
  [:map
   [:icon-class {:optional true} [:maybe :string]]
   [:icon-size {:optional true} [:maybe [:enum "s" "m" "l"]]]
   [:icon
    [:and :string [:fn #(contains? icon-list %)]]]
   ;; Indicates that the button has a flyout menu, and should display an indicator
   [:flyout-indicator {:optional true} [:maybe :boolean]]])

(mf/defc icon-button-internal*
  {::mf/schema schema:icon-button-internal
   ::mf/memo true}
  [{:keys [icon icon-class icon-size flyout-indicator children] :rest props}]
  [:> :button props
   [:> icon* {:icon-id icon
              :aria-hidden true
              :class icon-class
              :size icon-size}]
   (when flyout-indicator
     [:svg {:view-box "0 0 6 6"
            :aria-hidden true
            :class (stl/css :flyout-indicator)}
      [:path {:d "M4,2 L4,3.15 C4,3.62 3.62,4 3.15,4 L2,4"
              :stroke-linecap "round"}]])
   children])

(mf/defc icon-button*
  {::mf/schema schema:icon-button
   ::mf/memo true}
  [{:keys [class icon icon-class icon-size variant aria-label children tooltip-placement tooltip-class type flyout-indicator has-tooltip] :rest props}]
  (let [variant (d/nilv variant "primary")
        flyout-indicator (d/nilv flyout-indicator false)
        has-tooltip (d/nilv has-tooltip true)
        button-ref (mf/use-ref nil)
        tooltip-id (mf/use-id)

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
                          :ref button-ref
                          :type (d/nilv type "button")
                          :icon icon
                          :icon-size icon-size
                          :icon-class icon-class
                          :flyout-indicator flyout-indicator})]

    (if has-tooltip
      (let [tooltip-props (mf/spread-props props {:aria-labelledby tooltip-id})]
        [:> tooltip* {:content aria-label
                      :class tooltip-class
                      :trigger-ref button-ref
                      :placement tooltip-placement
                      :id tooltip-id}
         [:> icon-button-internal* tooltip-props children]])

      (let [no-tooltip-props (mf/spread-props props {:aria-label aria-label})]
        [:> icon-button-internal* no-tooltip-props children]))))
