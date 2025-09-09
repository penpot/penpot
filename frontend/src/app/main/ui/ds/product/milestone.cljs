;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.product.milestone
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.schema :as sm]
   [app.common.time :as ct]
   [app.common.types.profile :refer [schema:profile]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.avatar :refer [avatar*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private schema:callback
  [:maybe [:fn fn?]])

(def ^:private schema:milestone
  [:map
   [:class {:optional true} :string]
   [:active {:optional true} :boolean]
   [:editing {:optional true} :boolean]
   [:locked {:optional true} :boolean]
   [:profile {:optional true} schema:profile]
   [:label :string]
   [:created-at ::ct/inst]
   [:on-open-menu {:optional true} schema:callback]
   [:on-focus-menu {:optional true} schema:callback]
   [:on-blur-menu {:optional true} schema:callback]
   [:on-key-down-input {:optional true} schema:callback]])

(mf/defc milestone*
  {::mf/schema (sm/schema schema:milestone)}
  [{:keys [class active editing locked label created-at profile
           on-open-menu on-focus-input on-blur-input on-key-down-input] :rest props}]
  (let [class'
        (stl/css-case :milestone true
                      :is-selected active)
        props
        (mf/spread-props props
                         {:class [class class']
                          :data-testid "milestone"})
        created-at
        (if (ct/inst? created-at)
          created-at
          (ct/inst created-at))]

    [:> :div props
     [:> avatar* {:profile profile
                  :variant "S"
                  :class (stl/css :avatar)}]

     (if ^boolean editing
       [:> input*
        {:class (stl/css :name-input)
         :variant "seamless"
         :default-value label
         :auto-focus true
         :on-focus on-focus-input
         :on-blur on-blur-input
         :on-key-down on-key-down-input}]
       [:div {:class (stl/css :name-wrapper)}
        [:> text*  {:as "span" :typography t/body-small :class (stl/css :name)} label]
        (when locked
          [:> icon* {:icon-id i/lock :class (stl/css :lock-icon)}])])

     [:*
      [:time {:date-time (ct/format-inst created-at :iso)
              :class (stl/css :date)}
       (ct/timeago created-at)]

      [:div {:class (stl/css :milestone-buttons)}
       [:> icon-button* {:class (stl/css :menu-button)
                         :variant "ghost"
                         :icon i/menu
                         :aria-label (tr "workspace.versions.version-menu")
                         :on-click on-open-menu}]]]]))


