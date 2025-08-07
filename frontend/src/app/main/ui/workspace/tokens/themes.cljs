;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.themes
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.main.ui.workspace.tokens.themes.theme-selector :refer [theme-selector]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc themes-header*
  {::mf/private true}
  []
  (let [ordered-themes
        (mf/deref refs/workspace-token-themes-no-hidden)

        can-edit?
        (mf/use-ctx ctx/can-edit?)

        open-modal
        (mf/use-fn
         (fn [e]
           (dom/stop-propagation e)
           (modal/show! :tokens/themes {})))]

    [:div {:class (stl/css :themes-wrapper)}
     [:> text* {:as "div" :typography "headline-small" :class (stl/css :themes-header)} (tr "labels.themes")]
     (if (empty? ordered-themes)
       [:div {:class (stl/css :empty-theme-wrapper)}
        [:> text* {:as "span" :typography "body-small" :class (stl/css :empty-state-message)}
         (tr "workspace.tokens.no-themes")]
        (when can-edit?
          [:button {:on-click open-modal
                    :class (stl/css :create-theme-button)}
           (tr "workspace.tokens.create-one")])]
       (if can-edit?
         [:div {:class (stl/css :theme-selector-wrapper)}
          [:& theme-selector]
          [:> button* {:variant "secondary"
                       :class (stl/css :edit-theme-button)
                       :on-click open-modal}
           (tr "labels.edit")]]
         [:div {:title (when-not can-edit?
                         (tr "workspace.tokens.no-permission-themes"))}
          [:& theme-selector]]))]))
