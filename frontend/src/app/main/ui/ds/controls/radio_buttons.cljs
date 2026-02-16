;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.radio-buttons
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon-list]]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]
   [rumext.v2.util :as mfu]))

(def ^:private schema:radio-button
  [:map
   [:id :string]
   [:icon {:optional true}
    [:and :string [:fn #(contains? icon-list %)]]]
   [:label :string]
   [:value [:or :keyword :string]]
   [:disabled {:optional true} :boolean]])

(def ^:private schema:radio-buttons
  [:map
   [:class {:optional true} :string]
   [:variant {:optional true}
    [:maybe [:enum "primary" "secondary" "ghost" "destructive" "action"]]]
   [:extended {:optional true} :boolean]
   [:name {:optional true} :string]
   [:selected {:optional true}
    [:maybe [:or :keyword :string]]]
   [:allow-empty {:optional true} :boolean]
   [:options [:vector {:min 1} schema:radio-button]]
   [:on-change {:optional true} fn?]])

(mf/defc radio-buttons*
  {::mf/schema schema:radio-buttons}
  [{:keys [class variant extended name selected allow-empty options on-change] :rest props}]
  (let [options (if (array? options)
                  (mfu/bean options)
                  options)
        type    (if allow-empty "checkbox" "radio")
        variant (d/nilv variant "secondary")

        handle-click
        (mf/use-fn
         (fn [event]
           (let [target (dom/get-target event)
                 label  (dom/get-parent-with-data target "label")]
             (dom/prevent-default event)
             (dom/stop-propagation event)
             (dom/click label))))

        handle-change
        (mf/use-fn
         (mf/deps selected on-change)
         (fn [event]
           (let [input (dom/get-target event)
                 value (dom/get-target-val event)]
             (when (fn? on-change)
               (on-change value event))
             (dom/blur! input))))

        props
        (mf/spread-props props {:key (dm/str name "-" selected)
                                :class [class (stl/css-case :wrapper true
                                                            :extended extended)]})]

    [:> :div props
     (for [[idx {:keys [id class value label icon disabled]}] (d/enumerate options)]
       (let [checked? (= selected value)]
         [:label {:key idx
                  :html-for id
                  :data-label true
                  :data-testid id
                  :class [class (stl/css-case :label true
                                              :extended extended)]}

          (if (some? icon)
            [:> icon-button* {:variant variant
                              :on-click handle-click
                              :aria-pressed checked?
                              :aria-label label
                              :icon icon
                              :disabled disabled}]
            [:> button* {:variant variant
                         :on-click handle-click
                         :aria-pressed checked?
                         :class (stl/css-case :button true
                                              :extended extended)
                         :disabled disabled}
             label])

          [:input {:id id
                   :class (stl/css :input)
                   :on-change handle-change
                   :type type
                   :name name
                   :disabled disabled
                   :value value
                   :default-checked checked?}]]))]))
