;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sets-context-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data.macros :as dm]
   [app.main.data.tokens :as dt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private ref:token-sets-context-menu
  (l/derived :token-set-context-menu refs/workspace-tokens))

(defn- prevent-default
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event))

(mf/defc menu-entry*
  [{:keys [title value on-click]}]
  [:li {:class (stl/css :context-menu-item)
        :data-value value
        :on-click on-click}
   [:span {:class (stl/css :title)} title]])

(mf/defc menu*
  {::mf/private true}
  [{:keys [is-group id path]}]
  (let [create-set-at-path
        (mf/use-fn (mf/deps path) #(st/emit! (dt/start-token-set-creation path)))

        on-edit
        (mf/use-fn
         (mf/deps id)
         (fn []
           (st/emit! (dt/start-token-set-edition id))))

        on-delete
        (mf/use-fn
         (mf/deps is-group path)
         #(st/emit! (dt/delete-token-set-path is-group path)))]

    [:ul {:class (stl/css :context-list)}
     (when is-group
       [:> menu-entry* {:title (tr "workspace.token.add-set-to-group") :on-click create-set-at-path}])
     [:> menu-entry* {:title (tr "labels.rename") :on-click on-edit}]
     [:> menu-entry* {:title (tr "labels.delete")  :on-click on-delete}]]))

(mf/defc token-set-context-menu*
  []
  (let [{:keys [position is-group id path]}
        (mf/deref ref:token-sets-context-menu)

        position-top
        (+ (dm/get-prop position :y) 5)

        position-left
        (+ (dm/get-prop position :x) 5)

        on-close
        (mf/use-fn #(st/emit! (dt/assign-token-set-context-menu nil)))]

    [:& dropdown {:show (some? position)
                  :on-close on-close}
     [:div {:class (stl/css :token-set-context-menu)
            :data-testid "tokens-context-menu-for-set"
            :style {:top position-top
                    :left position-left}
            :on-context-menu prevent-default}
      [:> menu* {:is-group is-group
                 :id id
                 :path path}]]]))
