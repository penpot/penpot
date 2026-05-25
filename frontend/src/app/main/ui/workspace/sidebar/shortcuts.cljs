;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.main.ui.workspace.sidebar.shortcuts
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.main.data.dashboard.shortcuts]
   [app.main.data.shortcuts :as ds]
   [app.main.data.viewer.shortcuts]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.path.shortcuts]
   [app.main.data.workspace.shortcuts]
   [app.main.data.workspace.shortcuts.customize :as customize]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.ds.product.panel-title :refer [panel-title*]]
   [app.main.ui.shortcuts :as ss]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.strings :refer [matches-search]]
   [clojure.set :as set]
   [rumext.v2 :as mf]))

(def ^:private workspace-shortcuts-raw
  (d/deep-merge app.main.data.workspace.path.shortcuts/shortcuts
                app.main.data.workspace.shortcuts/shortcuts))

(mf/defc shortcuts-container*
  [{:keys [class]}]
  (let [profile                      (mf/deref refs/profile)
        custom-shortcuts             (get-in profile [:props :custom-shortcuts])

        workspace-shortcuts-custom   (ds/apply-custom-overrides workspace-shortcuts-raw custom-shortcuts)

        all-workspace-shortcuts      (->> workspace-shortcuts-custom
                                          (ss/add-translation :sc)
                                          (into {}))

        dashboard-shortcuts-custom     (ds/apply-custom-overrides app.main.data.dashboard.shortcuts/shortcuts custom-shortcuts)

        dashboard-shortcuts          (->> dashboard-shortcuts-custom
                                          (ss/add-translation :sc)
                                          (into {}))

        viewer-shortcuts-custom     (ds/apply-custom-overrides app.main.data.viewer.shortcuts/shortcuts custom-shortcuts)

        viewer-shortcuts             (->> viewer-shortcuts-custom
                                          (ss/add-translation :sc)
                                          (into {}))

        open-sections                (mf/use-state [[1]])
        filter-term                  (mf/use-state "")

        close-fn                     #(st/emit! (dw/toggle-layout-flag :shortcuts))

        {:keys [all-shortcuts all-sc-names all-sub-names all-section-names]}
        (ss/build-all-shortcuts all-workspace-shortcuts dashboard-shortcuts viewer-shortcuts)

        all-item-names              (concat all-sc-names all-sub-names all-section-names)
        match-any?                  (some #(matches-search % @filter-term) all-item-names)

        manage-sections
        (fn [item]
          (fn [event]
            (dom/stop-propagation event)
            (let [is-present? (some #(= % item) @open-sections)
                  new-value (if is-present?
                              (filterv (fn [element] (not= element item)) @open-sections)
                              (conj @open-sections item))]
              (reset! open-sections new-value))))

        add-ids (fn [acc node]
                  (let [id (:id node)
                        addition (case (count id)
                                   1 id
                                   2 [[(first id)] id]
                                   3 [[(first id)] [(first id) (second id)]]
                                   "default" nil)]
                    (if (= 1 (count addition))
                      (conj acc addition)
                      (into [] (concat acc addition)))))

        manage-section-on-search
        (fn [section term]
          (let [node-seq (tree-seq :children #(vals (:children %)) (get all-shortcuts section))]
            (reduce (fn [acc node]
                      (if (matches-search (:translation node) term)
                        (add-ids acc node)
                        acc))
                    []
                    node-seq)))

        manage-sections-on-search
        (fn [term]
          (if (= term "")
            (reset! open-sections [[1]])
            (let [ids (set/union (manage-section-on-search :basics term)
                                 (manage-section-on-search :workspace term)
                                 (manage-section-on-search :dashboard term)
                                 (manage-section-on-search :viewer term))]
              (reset! open-sections ids))))

        on-search-term-change-2
        (mf/use-callback
         (fn [value]
           (manage-sections-on-search value)
           (reset! filter-term value)))
        on-search-clear-click
        (mf/use-callback
         (fn [_]
           (reset! open-sections [[1]])
           (reset! filter-term "")))

        on-reset-shortcut
        (mf/use-callback
         (fn [shortcut-key]
           (st/emit! (customize/reset-custom-shortcut shortcut-key))))

        on-reset-all
        (mf/use-callback
         (fn [_]
           (st/emit! (customize/reset-all-custom-shortcuts))))]

    [:div {:class (dm/str class " " (stl/css :shortcuts))}
     [:> panel-title* {:class (stl/css :shortcuts-title)
                       :text (tr "shortcuts.title")
                       :on-close close-fn}]

     [:div {:class (stl/css :search-field)}
      [:> search-bar* {:on-change on-search-term-change-2
                       :on-clear on-search-clear-click
                       :value @filter-term
                       :placeholder (tr "shortcuts.title")
                       :icon-id i/search
                       :auto-focus true}]
      (when (seq custom-shortcuts)
        [:> icon-button* {:variant "ghost"
                          :aria-label (tr "shortcuts.reset-all")
                          :on-click on-reset-all
                          :icon i/reload
                          :icon-size "s"}])]

     (if match-any?
       [:div {:class (stl/css :shortcuts-list)}
        (for [section all-shortcuts]
          (let [[section-key _] section]
            [:> ss/shortcut-section* {:key (name section-key)
                                      :section section
                                      :manage-sections manage-sections
                                      :open-sections open-sections
                                      :filter-term filter-term
                                      :editable? false
                                      :custom-shortcuts custom-shortcuts
                                      :all-shortcuts all-workspace-shortcuts
                                      :on-reset on-reset-shortcut}]))]
       [:div {:class (stl/css :not-found)} (tr "shortcuts.not-found")])]))
