;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.product.milestone
  (:require-macros
   [app.common.data.macros :as dm]
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.avatar :refer [avatar*]]
   [app.main.ui.ds.utilities.date :refer [date* valid-date?]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(def ^:private schema:milestone
  [:map
   [:class {:optional true} :string]
   [:active {:optional true} :boolean]
   [:editing {:optional true} :boolean]
   [:autosaved {:optional true} :boolean]
   [:versionToggled {:optional true} :boolean]
   [:userName :string]
   [:userAvatar {:optional true} [:maybe :string]]
   [:userColor :string]
   [:label :string]
   [:date [:fn valid-date?]]
   [:autosavedMessage :string]
   [:snapshots [:vector [:fn valid-date?]]]])

(mf/defc milestone*
  {::mf/props :obj
   ::mf/schema schema:milestone}
  [{:keys [class active editing autosaved versionToggled
           userName userAvatar userColor label date
           autosavedMessage snapshots] :rest props}]

  (let [class (d/append-class class (stl/css-case :milestone true :is-selected active))
        props (mf/spread-props props {:class class :data-testid "milestone"})]
    [:> "div" props

     (when-not autosaved
       [:> avatar* {:name userName :url userAvatar :color userColor :variant "S" :class (stl/css :avatar)}])

     (if editing
       [:> input* {:class (stl/css :name-input) :default-value label}]
       [:> text*  {:as "span" :typography t/body-medium :class (stl/css :name)} label])

     (if autosaved
       [:div {:class (stl/css :snapshots)}
        [:button {:class (stl/css :toggle-snapshots)
                  :aria-label (tr "workspace.versions.expand-snapshot")
                  ;;:on-click handle-toggle-expand
                  }
         [:> i/icon* {:icon-id i/clock :class (stl/css :icon-clock)}]
         [:> text* {:as "span" :typography t/body-small :class (stl/css :name)} autosavedMessage]
         [:> i/icon* {:icon-id i/arrow :class (stl/css-case :icon-arrow true :toggled versionToggled)}]]

        (when versionToggled
          (for [[idx d] (d/enumerate snapshots)]
            [:div {:key (dm/str "entry-" idx)
                   :class (stl/css :version-entry)}
             [:> date* {:date d :class (stl/css :date)}]
             [:> icon-button* {:class (stl/css :entry-button)
                               :variant "ghost"
                               :icon "menu"
                               :aria-label (tr "workspace.versions.version-menu")
                               ;;:on-click handle-open-menu
                               }]]))]

       [:*
        [:> date*   {:date date :class (stl/css :date)}]
        [:div {:class (stl/css :milestone-buttons)}
         [:> icon-button* {:class (stl/css :menu-button)
                           :variant "ghost"
                           :icon "pin"
                           :aria-label (tr "workspace.versions.version-menu")
                           ;;:on-click handle-open-menu
                           }]
         [:> icon-button* {:class (stl/css :menu-button)
                           :variant "ghost"
                           :icon "menu"
                           :aria-label (tr "workspace.versions.version-menu")
                           ;;:on-click handle-open-menu
                           }]]])]))
