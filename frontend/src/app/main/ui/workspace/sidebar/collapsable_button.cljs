;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.collapsable-button
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc collapsed-button
  {::mf/wrap-props false}
  []
  (let [new-css? (mf/use-ctx ctx/new-css-system)
        on-click (mf/use-fn #(st/emit! (dw/toggle-layout-flag :collapse-left-sidebar)))]
    (if ^boolean new-css?
      [:div {:class (dom/classnames (css :collapsed-sidebar) true)}
       [:div {:class (dom/classnames (css :collapsed-title) true)}
        [:button {:class (dom/classnames (css :collapsed-button) true)
                  :on-click on-click
                  :aria-label (tr "workspace.sidebar.expand")}
         i/arrow-refactor]]]
      [:button.collapse-sidebar.collapsed
       {:on-click on-click
        :aria-label (tr "workspace.sidebar.expand")}
       i/arrow-slide])))
