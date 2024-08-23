;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.modals.themes
  (:require-macros [app.main.style :as stl])
  (:require
   [app.main.data.modal :as modal]
   [app.main.ui.components.radio-buttons :refer [radio-button radio-buttons]]
   [cuerdas.core :as str]
   [app.main.data.tokens :as wdt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [rumext.v2 :as mf]))


(def ^:private close-icon
  (i/icon-xref :close (stl/css :close-icon)))

(mf/defc empty-themes
  [{:keys []}]
  "Empty")


(mf/defc switch
  [{:keys [selected? name on-change]}]
  (let [selected (if selected? :on :off)]
    [:& radio-buttons {:selected selected
                       :on-change on-change
                       :name name}
     [:& radio-button {:id :on
                       :value :on
                       :icon i/tick
                       :label ""}]
     [:& radio-button {:id :off
                       :value :off
                       :icon i/close
                       :label ""}]]))

(mf/defc themes-overview
  [{:keys []}]
  (let [active-theme-ids (mf/deref refs/workspace-active-theme-ids)
        themes (mf/deref refs/workspace-ordered-token-themes)]
    [:ul
     (for [[group themes] themes]
       [:li
        {:key (str "token-theme-group" group)}
        group
        [:ul
         (for [{:keys [id name] :as _theme} themes]
           [:li {:key (str "tokene-theme-" id)}
            [:div.spaced
             name
             [:div.spaced
              [:& switch {:name (str "Theme" name)
                          :on-change #(st/emit! (wdt/toggle-token-theme id))
                          :selected? (get active-theme-ids id)}]
              [:button {:on-click (fn [e]
                                    (dom/prevent-default e)
                                    (dom/stop-propagation e)
                                    (st/emit! (wdt/delete-token-theme id)))}
               "🗑️"]]]])]])]))

(mf/defc edit-theme
  [{:keys []}]
  "Edit Theme")

(mf/defc themes
  [{:keys [] :as _args}]
  (let [active-theme-ids (mf/deref refs/workspace-active-theme-ids)
        themes (mf/deref refs/workspace-ordered-token-themes)
        _ (js/console.log "themes" themes)
        state (mf/use-state (if (empty? themes)
                              :empty-themes
                              :themes-overview))]
    (case @state
      :empty-themes [:& empty-themes]
      :themes-overview [:& themes-overview]
      :edit-theme [:& edit-theme])))

(mf/defc modal
  {::mf/wrap-props false}
  [{:keys [] :as _args}]
  (let [handle-close-dialog (mf/use-callback #(st/emit! (modal/hide)))]
    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:button {:class (stl/css :close-btn) :on-click handle-close-dialog} close-icon]
      [:div {:class (stl/css :modal-title)} "Themes"]
      [:div {:class (stl/css :modal-content)}
       [:& themes]]]]))
