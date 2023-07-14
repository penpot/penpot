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

(mf/defc title-bar
  {::mf/wrap-props false}
  [{:keys [collapsable? collapsed? on-collapsed title children on-btn-click btn-children klass]}]
  (let [klass (dm/str (stl/css :title-bar) " " klass)]
    [:div {:class klass}
     (if collapsable?
       [:button {:class (stl/css :toggle-btn) :on-click on-collapsed}
        [:span {:class (stl/css-case
                        :collased-icon true
                         :rotated collapsed?)}
         i/arrow-refactor]
        [:div {:class (stl/css :title)} title]]
       [:div {:class (stl/css :title-only)} title])
     children
     (when (some? on-btn-click)
       [:button {:class (stl/css :title-button)
                 :on-click on-btn-click}
        btn-children])]))
