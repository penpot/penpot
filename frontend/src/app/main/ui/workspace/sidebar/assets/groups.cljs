;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.assets.groups
  (:require-macros [app.main.style :refer [css]])
  (:require
   [app.common.pages.helpers :as cph]
   [app.common.spec :as us]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.store :as st]
   [app.main.ui.components.forms :as fm]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.assets.common :as cmm]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [cljs.spec.alpha :as s]
   [rumext.v2 :as mf]))

(mf/defc asset-group-title
  [{:keys [file-id section path group-open? on-rename on-ungroup]}]
  (when-not (empty? path)
    (let [[other-path last-path truncated] (cph/compact-path path 35 true)
          menu-state     (mf/use-state cmm/initial-context-menu-state)
          new-css-system (mf/use-ctx ctx/new-css-system)
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
      (if new-css-system
        [:div {:class (dom/classnames (css :group-title) true)
               :on-context-menu on-context-menu}
         [:& title-bar {:collapsable? true
                        :collapsed?   (not group-open?)
                        :on-collapsed  on-fold-group
                        :title        (mf/html [:* (when-not (empty? other-path)
                                                     [:span {:class (dom/classnames (css :pre-path) true)
                                                             :title (when truncated path)}
                                                      other-path "\u00A0\u2022\u00A0"])
                                                [:span {:class (dom/classnames (css :path) true)
                                                        :title (when truncated path)}
                                                 last-path]])}]
         [:& cmm/assets-context-menu
          {:on-close on-close-menu
           :state @menu-state
           :options [{:option-name    (tr "workspace.assets.rename")
                      :id             "assets-rename-group"
                      :option-handler #(on-rename % path last-path)}
                     {:option-name    (tr "workspace.assets.ungroup")
                      :id             "assets-ungroup-group"
                      :option-handler  #(on-ungroup path)}]}]]


        [:div.group-title {:class (when-not group-open? "closed")
                           :on-click on-fold-group
                           :on-context-menu on-context-menu}
         [:span i/arrow-slide]
         (when-not (empty? other-path)
           [:span.dim {:title (when truncated path)}
            other-path "\u00A0/\u00A0"])
         [:span {:title (when truncated path)}
          last-path]
         [:& cmm/assets-context-menu
          {:on-close on-close-menu
           :state @menu-state
           :options [[(tr "workspace.assets.rename") #(on-rename % path last-path)]
                     [(tr "workspace.assets.ungroup") #(on-ungroup path)]]}]]))))

(defn group-assets
  "Convert a list of assets in a nested structure like this:

    {'': [{assetA} {assetB}]
     'group1': {'': [{asset1A} {asset1B}]
                'subgroup11': {'': [{asset11A} {asset11B} {asset11C}]}
                'subgroup12': {'': [{asset12A}]}}
     'group2': {'subgroup21': {'': [{asset21A}}}}
  "
  [assets reverse-sort?]
  (when-not (empty? assets)
    (reduce (fn [groups {:keys [path] :as asset}]
              (let [path (cph/split-path (or path ""))]
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

(s/def ::asset-name ::us/not-empty-string)
(s/def ::name-group-form
  (s/keys :req-un [::asset-name]))

(mf/defc name-group-dialog
  {::mf/register modal/components
   ::mf/register-as :name-group-dialog}
  [{:keys [path last-path accept] :as ctx
    :or {path "" last-path ""}}]
  (let [initial (mf/use-memo
                 (mf/deps last-path)
                 (constantly {:asset-name last-path}))
        form  (fm/use-form :spec ::name-group-form
                           :validators [(fm/validate-not-empty :name (tr "auth.name.not-all-space"))
                                        (fm/validate-length :name fm/max-length-allowed (tr "auth.name.too-long"))]
                           :initial initial)

        create? (empty? path)

        on-close (mf/use-fn #(modal/hide!))

        on-accept
        (mf/use-fn
         (mf/deps form)
         (fn [_]
           (let [asset-name (get-in @form [:clean-data :asset-name])]
             (if create?
               (accept asset-name)
               (accept path asset-name))
             (modal/hide!))))]

    [:div.modal-overlay
     [:div.modal-container.confirm-dialog
      [:div.modal-header
       [:div.modal-header-title
        [:h2 (if create?
               (tr "workspace.assets.create-group")
               (tr "workspace.assets.rename-group"))]]
       [:div.modal-close-button
        {:on-click on-close} i/close]]

      [:div.modal-content.generic-form
       [:& fm/form {:form form :on-submit on-accept}
        [:& fm/input {:name :asset-name
                      :auto-focus? true
                      :label (tr "workspace.assets.group-name")
                      :hint (tr "workspace.assets.create-group-hint")}]]]

      [:div.modal-footer
       [:div.action-buttons
        [:input.cancel-button
         {:type "button"
          :value (tr "labels.cancel")
          :on-click on-close}]

        [:input.accept-button.primary
         {:type "button"
          :class (when-not (:valid @form) "btn-disabled")
          :disabled (not (:valid @form))
          :value (if create? (tr "labels.create") (tr "labels.rename"))
          :on-click on-accept}]]]]]))
