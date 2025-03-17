;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.components.title-bar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.ui.icons :as i]
   [rumext.v2 :as mf]))

(def ^:private chevron-icon
  (i/icon-xref :arrow (stl/css :chevron-icon)))

(mf/defc title-bar
  {::mf/props :obj}
  [{:keys [class collapsable collapsed title children
           btn-children all-clickable add-icon-gap
           on-collapsed on-btn-click]}]
  (let [klass     (stl/css-case :title-bar true
                                :all-clickable all-clickable)
        klass     (dm/str klass " " class)]
    [:div {:class klass}
     (if ^boolean collapsable
       [:div {:class (stl/css :title-wrapper)}
        (if ^boolean all-clickable
          [:button {:class (stl/css :toggle-btn)
                    :on-click on-collapsed}
           [:span {:class (stl/css-case
                           :collapsabled-icon true
                           :collapsed collapsed)}
            chevron-icon]
           [:div {:class (stl/css :title)} title]]
          [:*
           [:button {:class (stl/css-case
                             :collapsabled-icon true
                             :collapsed collapsed)
                     :on-click on-collapsed}
            chevron-icon]
           [:div {:class (stl/css :title)}
            title]])]
       [:div {:class (stl/css-case
                      :title-only true
                      :title-only-icon-gap add-icon-gap)}
        title])
     children
     (when (some? on-btn-click)
       [:button {:class (stl/css :title-button)
                 :on-click on-btn-click}
        btn-children])]))

(mf/defc inspect-title-bar*
  [{:keys [class title]}]
  [:div {:class (dm/str (stl/css :title-bar) " " class)}
   [:div {:class (stl/css :title-only :inspect-title)} title]])
