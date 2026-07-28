;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.tokens.tokens-source
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.typography.text :refer [text*]]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc tokens-source-info*
  {::mf/private true}
  [{:keys [tokens-source] :as props}]

  (let [files              (mf/deref refs/files)
        tokens-source-file (get files tokens-source)

        team-id
        (mf/use-ctx ctx/current-team-id)

        open-library-new-window
        (mf/use-fn
         (fn []
           (st/emit! (rt/nav :workspace
                             {:team-id team-id
                              :file-id tokens-source
                              :layout :tokens}
                             ::rt/new-window true))))]

    [:div {:class (stl/css :tokens-source-wrapper)}
     [:div {:class (stl/css :tokens-source-header)}
      [:> text* {:as "div" :typography "headline-small" :class (stl/css :tokens-source-title)}
       (:name tokens-source-file)]
      [:span {:class (stl/css :replace-this-by-a-badge-component)}
       (tr "workspace.tokens.connected-library")]]
     [:> icon-button*
      {:variant "ghost"
       :aria-label (tr "workspace.tokens.open-connected-library")
       :on-click open-library-new-window
       :icon "open-link"}]]))
