;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.theme-select
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.modal :as modal]
   [app.main.data.tokens :as wdt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.dropdown :refer [dropdown]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))

(mf/defc list-items
  [{:keys [themes active-theme-ids on-close grouped?]}]
  (when (seq themes)
    [:ul
     (for [{:keys [id name]} themes
           :let [selected? (get active-theme-ids id)]]
       [:li {:key id
             :class (stl/css-case
                     :checked-element true
                     :sub-item grouped?
                     :is-selected selected?)
             :on-click (fn [e]
                         (dom/stop-propagation e)
                         (st/emit! (wdt/toggle-token-theme id))
                         (on-close))}
        [:span {:class (stl/css :label)} name]
        [:span {:class (stl/css :check-icon)} i/tick]])]))

(mf/defc theme-options
  [{:keys [on-close]}]
  (let [active-theme-ids (mf/deref refs/workspace-active-theme-ids)
        ordered-themes (mf/deref refs/workspace-ordered-token-themes)
        grouped-themes (dissoc ordered-themes nil)
        ungrouped-themes (get ordered-themes nil)]
    [:ul
     [:& list-items {:themes ungrouped-themes
                     :active-theme-ids active-theme-ids
                     :on-close on-close}]
     (for [[group themes] grouped-themes]
       [:li {:key group}
        (when group
          [:span {:class (stl/css :group)} group])
        [:& list-items {:themes themes
                        :active-theme-ids active-theme-ids
                        :on-close on-close
                        :grouped? true}]])
     [:li {:class (stl/css-case :checked-element true
                                :checked-element-button true)
           :on-click #(modal/show! :tokens/themes {})}
      [:span "Edit themes"]
      [:span {:class (stl/css :icon)} i/arrow]]]))

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
       [:& theme-options {:on-close on-close-dropdown}]]]]))
