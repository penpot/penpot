;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.assets.groups
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.schema :as sm]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc asset-group-title
  [{:keys [file-id section path group-open? on-rename on-ungroup]}]
  (when-not (empty? path)
    (let [[other-path last-path truncated] (cfh/compact-path path 35 true)
          menu-state     (mf/use-state cmm/initial-context-menu-state)
          on-fold-group
          (mf/use-fn
           (mf/deps file-id section path group-open?)
           (fn [event]
             (dom/stop-propagation event)
             (st/emit! (dw/set-assets-group-open file-id
                                                 section
                                                 path
                                                 (not group-open?)))))
          on-context-menu
          (mf/use-fn
           (fn [event]
             (dom/prevent-default event)
             (let [pos (dom/get-client-position event)]
               (swap! menu-state cmm/open-context-menu pos))))

          on-close-menu
          (mf/use-fn #(swap! menu-state cmm/close-context-menu))]
      [:div {:class (stl/css :group-title)
             :on-context-menu on-context-menu}
       [:& title-bar {:collapsable    true
                      :collapsed      (not group-open?)
                      :all-clickable  true
                      :on-collapsed   on-fold-group
                      :title          (mf/html [:* (when-not (empty? other-path)
                                                     [:span {:class (stl/css :pre-path)
                                                             :title (when truncated path)}
                                                      other-path "\u00A0\u2022\u00A0"])
                                                [:span {:class (stl/css :path)
                                                        :title (when truncated path)}
                                                 last-path]])}]
       [:& cmm/assets-context-menu
        {:on-close on-close-menu
         :state @menu-state
         :options [{:name    (tr "workspace.assets.rename")
                    :id      "assets-rename-group"
                    :handler #(on-rename % path last-path)}
                   {:name    (tr "workspace.assets.ungroup")
                    :id      "assets-ungroup-group"
                    :handler  #(on-ungroup path)}]}]])))

(defn group-assets
  "Convert a list of assets in a nested structure like this:

    {'': [assetA assetB]
     'group1': {'': [asset1A asset1B]
                'subgroup11': {'': [asset11A asset11B asset11C]}
                'subgroup12': {'': [asset12A]}}
     'group2': {'subgroup21': {'': [asset21A]}}}
  "
  [assets reverse-sort?]
  (when-not (empty? assets)
    (reduce (fn [groups {:keys [path] :as asset}]
              (let [path (cfh/split-path (or path ""))]
                (update-in groups
                           (conj path "")
                           (fn [group]
                             (if group
                               (conj group asset)
                               [asset])))))
            (sorted-map-by (fn [key1 key2]
                             (if reverse-sort?
                               (compare key2 key1)
                               (compare key1 key2))))
            assets)))

(def ^:private schema:group-form
  [:map {:title "GroupForm"}
   [:name [::sm/text {:max 250}]]])

(mf/defc name-group-dialog
  {::mf/register modal/components
   ::mf/register-as :name-group-dialog}
  [{:keys [path last-path accept] :as ctx
    :or {path "" last-path ""}}]
  (let [initial  (mf/with-memo [last-path]
                   {:asset-name last-path})
        form     (fm/use-form :schema schema:group-form
                              :initial initial)

        create?  (empty? path)

        on-accept
        (mf/use-fn
         (mf/deps form)
         (fn [_]
           (let [asset-name (get-in @form [:clean-data :name])]
             (if create?
               (accept asset-name)
               (accept path asset-name))
             (modal/hide!))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-container)}
      [:div {:class (stl/css :modal-header)}
       [:h2  {:class (stl/css :modal-title)}
        (if create?
          (tr "workspace.assets.create-group")
          (tr "workspace.assets.rename-group"))]
       [:button {:class (stl/css :modal-close-btn)
                 :on-click modal/hide!} i/close]]

      [:div {:class (stl/css :modal-content)}
       [:& fm/form {:form form :on-submit on-accept}
        [:& fm/input {:name :name
                      :class (stl/css :input-wrapper)
                      :auto-focus? true
                      :label (tr "workspace.assets.group-name")
                      :hint (tr "workspace.assets.create-group-hint")}]]]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:input
         {:class (stl/css :cancel-button)
          :type "button"
          :value (tr "labels.cancel")
          :on-click modal/hide!}]

        [:input
         {:type "button"
          :class (stl/css-case :accept-btn true
                               :global/disabled (not (:valid @form)))
          :disabled (not (:valid @form))
          :value (if create? (tr "labels.create") (tr "labels.rename"))
          :on-click on-accept}]]]]]))
