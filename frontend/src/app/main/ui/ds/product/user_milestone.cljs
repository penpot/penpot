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
   [app.main.ui.ds.utilities.date :refer [valid-date?]]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.object :as obj]
   [app.util.time :as dt]
   [rumext.v2 :as mf]))

(def ^:private schema:milestone
  [:map
   [:class {:optional true} :string]
   [:active {:optional true} :boolean]
   [:editing {:optional true} :boolean]
   [:user
    [:map
     [:name {:optional true} [:maybe :string]]
     [:avatar {:optional true} [:maybe :string]]
     [:color {:optional true} [:maybe :string]]]]
   [:label :string]
   [:date [:fn valid-date?]]
   [:onOpenMenu {:optional true} [:maybe [:fn fn?]]]
   [:onFocusInput {:optional true} [:maybe [:fn fn?]]]
   [:onBlurInput {:optional true} [:maybe [:fn fn?]]]
   [:onKeyDownInput {:optional true} [:maybe [:fn fn?]]]])

(mf/defc user-milestone*
  {::mf/props :obj
   ::mf/schema schema:milestone}
  [{:keys [class active editing user label date
           onOpenMenu onFocusInput onBlurInput onKeyDownInput] :rest props}]
  (let [class (d/append-class class (stl/css-case :milestone true :is-selected active))
        props (mf/spread-props props {:class class :data-testid "milestone"})
        date (cond-> date (not (dt/datetime? date)) dt/datetime)
        time   (dt/timeago date)]
    [:> "div" props
     [:> avatar* {:name (obj/get user "name")
                  :url (obj/get user "avatar")
                  :color (obj/get user "color")
                  :variant "S" :class (stl/css :avatar)}]

     (if editing
       [:> input*
        {:class (stl/css :name-input)
         :variant "seamless"
         :default-value label
         :auto-focus true
         :on-focus onFocusInput
         :on-blur onBlurInput
         :on-key-down onKeyDownInput}]
       [:> text*  {:as "span" :typography t/body-small :class (stl/css :name)} label])

     [:*
      [:time {:dateTime (dt/format date :iso)
              :class (stl/css :date)} time]

      [:div {:class (stl/css :milestone-buttons)}
       [:> icon-button* {:class (stl/css :menu-button)
                         :variant "ghost"
                         :icon "menu"
                         :aria-label (tr "workspace.versions.version-menu")
                         :on-click onOpenMenu}]]]]))


