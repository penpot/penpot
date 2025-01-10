;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.collapsable-button
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc collapsed-button
  {::mf/wrap-props false}
  []
  (let [on-click (mf/use-fn #(st/emit! (dw/toggle-layout-flag :collapse-left-sidebar)))]
    [:div {:id "left-sidebar-aside"
           :data-size "0"
           :class (stl/css :collapsed-sidebar)}
     [:div {:class (stl/css :collapsed-title)}
      [:button {:class (stl/css :collapsed-button)
                :title (tr "workspace.sidebar.expand")
                :on-click on-click}
       [:> icon* {:icon-id "arrow"
                  :size "s"
                  :aria-label (tr "workspace.sidebar.expand")}]]]]))
