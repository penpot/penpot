;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.sets-context-menu
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.tokens :as wdt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.workspace.tokens.sets-context :as sets-context]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def sets-menu-ref
  (l/derived :token-set-context-menu refs/workspace-local))

(defn- prevent-default
  [event]
  (dom/prevent-default event)
  (dom/stop-propagation event))

(mf/defc menu-entry
  {::mf/props :obj}
  [{:keys [title value on-click]}]
  [:li {:class (stl/css :context-menu-item)
        :data-value value
        :on-click on-click}
   [:span {:class (stl/css :title)} title]])

(mf/defc menu
  [{:keys [group? path]}]
  (let [{:keys [on-edit]} (sets-context/use-context)
        edit-name (mf/use-fn
                   (mf/deps group?)
                   (fn []
                     (let [path (if group?
                                  (sets-context/set-group-path->id path)
                                  (sets-context/set-path->id path))]
                       (on-edit path))))
        delete-set (mf/use-fn #(st/emit! (wdt/delete-token-set-path group? path)))]
    [:ul {:class (stl/css :context-list)}
     ;; TODO Implement
     ;; (when (ctob/prefixed-set-path-final-group? prefixed-set-path)
     ;;   [:& menu-entry {:title "Add set to this group" :on-click js/console.log}])
     [:& menu-entry {:title (tr "labels.rename") :on-click edit-name}]
     [:& menu-entry {:title (tr "labels.delete")  :on-click delete-set}]]))

(mf/defc sets-context-menu
  []
  (let [mdata (mf/deref sets-menu-ref)
        top (+ (get-in mdata [:position :y]) 5)
        left (+ (get-in mdata [:position :x]) 5)
        width (mf/use-state 0)
        dropdown-ref (mf/use-ref)]
    (mf/use-effect
     (mf/deps mdata)
     (fn []
       (when-let [node (mf/ref-val dropdown-ref)]
         (reset! width (.-offsetWidth node)))))
    [:& dropdown {:show (boolean mdata)
                  :on-close #(st/emit! wdt/hide-token-set-context-menu)}
     [:div {:class (stl/css :token-set-context-menu)
            :ref dropdown-ref
            :style {:top top :left left}
            :on-context-menu prevent-default}
      [:& menu {:group? (:group? mdata)
                :path (:path mdata)}]]]))
