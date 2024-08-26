;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.theme-select
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.uuid :as uuid]
   [app.main.refs :as refs]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [rumext.v2 :as mf]))

(mf/defc theme-options
  [{:keys []}]
  (let [active-theme-ids (mf/deref refs/workspace-active-theme-ids)
        ordered-themes (mf/deref refs/workspace-ordered-token-themes)]
    [:ul
     (for [[group themes] ordered-themes]
       [:li {:key group}
        (when group
          [:span {:class (stl/css :group)} group])
        [:ul
         (for [{:keys [id name]} themes
               :let [selected? (get active-theme-ids id)]]
           [:li {:key id
                 :class (stl/css-case
                         :checked-element true
                         :is-selected selected?)}
            [:span {:class (stl/css :label)} name]
            [:span {:class (stl/css :check-icon)} i/tick]])]])]))

(mf/defc theme-select
  [{:keys []}]
  (let [;; Store
        temp-theme-id (mf/deref refs/workspace-temp-theme-id)
        active-theme-ids (-> (mf/deref refs/workspace-active-theme-ids)
                             (disj temp-theme-id))
        active-themes-count (count active-theme-ids)
        themes (mf/deref refs/workspace-token-themes)

        ;; Data
        current-label (cond
                        (> active-themes-count 1) (str active-themes-count " themes active")
                        (pos? active-themes-count) (get-in themes [(first active-theme-ids) :name])
                        :else "No theme active")

        ;; State
        state* (mf/use-state
                {:id (uuid/next)
                 :is-open? false})
        state (deref state*)
        is-open? (:is-open? state)

        ;; Dropdown
        dropdown-element* (mf/use-ref nil)
        on-close-dropdown (mf/use-fn #(swap! state* assoc :is-open? false))
        on-open-dropdown (mf/use-fn #(swap! state* assoc :is-open? true))]
    [:div {:on-click on-open-dropdown
           :class (stl/css :custom-select)}
     [:span {:class (stl/css :current-label)} current-label]
     [:span {:class (stl/css :dropdown-button)} i/arrow]
     [:& dropdown {:show is-open? :on-close on-close-dropdown}
      [:div {:ref dropdown-element*
             :class (stl/css :custom-select-dropdown)}
       [:& theme-options]]]]))
