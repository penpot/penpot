; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.viewport.top-bar
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.common :as dwc]
   [app.main.store :as st]
   [app.main.ui.workspace.viewport.grid-layout-editor :refer [grid-edition-actions]]
   [app.main.ui.workspace.viewport.path-actions :refer [path-actions*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; FIXME: this namespace should be renamed and all translation files
;; should also be renamed. But this should be done on development
;; branch.

(mf/defc view-only-bar*
  {::mf/private true}
  []
  (let [handle-close-view-mode
        (mf/use-fn
         (fn []
           (st/emit! :interrupt
                     (dw/set-options-mode :design)
                     (dwc/set-workspace-read-only false))))]
    [:div {:class (stl/css :viewport-actions)}
     [:div {:class (stl/css :viewport-actions-container)}
      [:div {:class (stl/css :viewport-actions-title)}
       [:> i18n/tr-html*
        {:tag-name "span"
         :content (tr "workspace.top-bar.view-only")}]]
      [:button {:class (stl/css :done-btn)
                :on-click handle-close-view-mode}
       (tr "workspace.top-bar.read-only.done")]]]))

(mf/defc path-edition-bar*
  [{:keys [layout edit-path-state shape]}]
  (let [rulers? (contains? layout :rulers)
        class   (stl/css-case
                 :viewport-actions-path true
                 :viewport-actions-no-rulers (not rulers?))]
    [:div {:class class}
     [:> path-actions* {:shape shape :state edit-path-state}]]))

(mf/defc grid-edition-bar*
  [{:keys [shape]}]
  [:& grid-edition-actions {:shape shape}])
