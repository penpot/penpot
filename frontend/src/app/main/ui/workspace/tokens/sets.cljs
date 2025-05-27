;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sets
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.tokens-lib :as ctob]
   [app.main.refs :as refs]
   [app.main.ui.components.title-bar :as title-bar]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as h]
   [app.main.ui.workspace.tokens.sets.sets-list :as tsets]
   [app.util.i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc token-sets-list*
  {::mf/private true}
  [{:keys [tokens-lib]}]
  (let [;; FIXME: This is an inneficient operation just for being
        ;; ability to check if there are some sets and lookup the
        ;; first one when no set is selected, should be REFACTORED; is
        ;; inneficient because instead of return the sets as-is (tree)
        ;; it firstly makes it a plain seq from tree.
        token-sets
        (some-> tokens-lib (ctob/get-sets))

        selected-token-set-name
        (mf/deref refs/selected-token-set-name)

        {:keys [token-set-edition-id
                token-set-new-path]}
        (mf/deref refs/workspace-tokens)]

    (if (and (empty? token-sets)
             (not token-set-new-path))

      (when-not token-set-new-path
        [:> tsets/inline-add-button*])

      [:> h/sortable-container {}
       [:> tsets/sets-list*
        {:tokens-lib tokens-lib
         :new-path token-set-new-path
         :edition-id token-set-edition-id
         :selected selected-token-set-name}]])))

(mf/defc token-sets-section*
  {::mf/private true}
  [props]

  (let [can-edit?
        (mf/use-ctx ctx/can-edit?)]

    [:*
     [:div {:class (stl/css :sidebar-header)}
        [:& title-bar/title-bar {:title (tr "labels.sets")}
         (when can-edit?
           [:> tsets/add-button*])]]

       [:> token-sets-list* props]]))
