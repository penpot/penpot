;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.product.user-milestone
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.ds.product.avatar :refer [avatar*]]
   [app.main.ui.ds.utilities.date :refer [date* valid-date?]]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [rumext.v2 :as mf]))

(def ^:private schema:milestone
  [:map
   [:class {:optional true} :string]
   [:active {:optional true} :boolean]
   [:editing {:optional true} :boolean]
   [:user
    [:map
     [:name :string]
     [:avatar {:optional true} [:maybe :string]]
     [:color :string]]]
   [:label :string]
   [:date [:fn valid-date?]]])

(mf/defc user-milestone*
  {::mf/props :obj
   ::mf/schema schema:milestone}
  [{:keys [class active editing user label date] :rest props}]
  (let [class (d/append-class class (stl/css-case :milestone true :is-selected active))
        props (mf/spread-props props {:class class :data-testid "milestone"})]
    [:> "div" props
     [:> avatar* {:name (obj/get user "name")
                  :url (obj/get user "avatar")
                  :color (obj/get user "color")
                  :variant "S" :class (stl/css :avatar)}]

     (if editing
       [:> input* {:class (stl/css :name-input) :default-value label}]
       [:> text*  {:as "span" :typography t/body-medium :class (stl/css :name)} label])

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
                         }]]]]))


