;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.title-bar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [rumext.v2 :as mf]))

(mf/defc title-bar*
  [{:keys [class collapsable collapsed title children
           btn-icon btn-title all-clickable add-icon-gap
           title-class on-collapsed on-btn-click]}]
  [:div {:class [(stl/css-case :title-bar true
                               :all-clickable all-clickable)
                 class]}

   (if ^boolean collapsable
     [:div {:class [(stl/css :title-wrapper) title-class]}

      (let [icon-id (if collapsed "arrow-right" "arrow-down")]
        (if ^boolean all-clickable
          [:button {:class (stl/css :icon-text-btn)
                    :on-click on-collapsed}
           [:> icon* {:icon-id icon-id
                      :size "s"
                      :class (stl/css :icon)}]
           [:div {:class (stl/css :title)} title]]
          [:*
           [:button {:class (stl/css :icon-btn)
                     :on-click on-collapsed}
            [:> icon* {:icon-id icon-id
                       :size "s"
                       :class (stl/css :icon)}]]
           [:div {:class (stl/css :title)} title]]))]

     [:div {:class [(stl/css-case :title-only true
                                  :title-only-icon-gap add-icon-gap)
                    title-class]}
      title])

   children

   (when (some? on-btn-click)
     [:> icon-button* {:variant "ghost"
                       :aria-label btn-title
                       :on-click on-btn-click
                       :icon btn-icon}])])


(mf/defc inspect-title-bar*
  [{:keys [class title]}]
  [:div {:class [(stl/css :title-bar) class]}
   [:div {:class (stl/css :title-only :inspect-title)} title]])
