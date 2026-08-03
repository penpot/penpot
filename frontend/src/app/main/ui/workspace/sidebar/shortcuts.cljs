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
   [app.config :as cf]
   [app.main.data.dashboard.shortcuts :as dsc]
   [app.main.data.shortcuts :as ds]
   [app.main.data.viewer.shortcuts :as vsc]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.path.shortcuts :as psc]
   [app.main.data.workspace.shortcuts :as wsc]
   [app.main.refs :as refs]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.button-link :as bl]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.ds.foundations.assets.icon :as i  :refer [icon*]]
   [app.main.ui.ds.product.panel-title :refer [panel-title*]]
   [app.main.ui.shortcuts :as ss]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.strings :refer [matches-search]]
   [clojure.set :as set]
   [rumext.v2 :as mf]))


(mf/defc shortcuts-container*
  [{:keys [class]}]
  (let [profile                      (mf/deref refs/profile)
        custom-shortcuts             (get-in profile [:props :custom-shortcuts])

        path-shortcuts-custom        (ds/apply-custom-overrides psc/shortcuts custom-shortcuts :path)

        workspace-shortcuts-custom   (ds/apply-custom-overrides wsc/shortcuts custom-shortcuts :workspace)

        workspace-shortcuts          (->> (d/deep-merge path-shortcuts-custom workspace-shortcuts-custom)
                                          (ss/add-translation :sc)
                                          (into {}))


        dashboard-shortcuts-custom   (ds/apply-custom-overrides dsc/shortcuts custom-shortcuts :dashboard)
        dashboard-shortcuts          (->> dashboard-shortcuts-custom
                                          (ss/add-translation :sc)
                                          (into {}))

        viewer-shortcuts-custom      (ds/apply-custom-overrides vsc/shortcuts custom-shortcuts :viewer)
        viewer-shortcuts             (->> viewer-shortcuts-custom
                                          (ss/add-translation :sc)
                                          (into {}))

        open-sections*               (mf/use-state [[:workspace]])
        open-sections                (deref open-sections*)
        filter-term*                 (mf/use-state "")
        filter-term                  (deref filter-term*)

        close-fn                     #(st/emit! (dw/toggle-layout-flag :shortcuts))

        {:keys [all-shortcuts all-sc-names all-sub-names all-section-names]}
        (ss/build-all-shortcuts workspace-shortcuts dashboard-shortcuts viewer-shortcuts)

        all-item-names              (concat all-sc-names all-sub-names all-section-names)
        match-any?                  (some #(matches-search % filter-term) all-item-names)

        manage-sections
        (fn [item]
          (fn [event]
            (dom/stop-propagation event)
            (let [is-present? (some #(= % item) open-sections)
                  new-value (if is-present?
                              (filterv (fn [element] (not= element item)) open-sections)
                              (conj open-sections item))]
              (reset! open-sections* new-value))))

        add-ids (fn [acc node]
                  (let [id      (:id node)
                        parents (when (> (count id) 1)
                                  (mapv (fn [n] (vec (take n id)))
                                        (range 1 (count id))))]
                    (into [] (concat acc parents))))

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
            (reset! open-sections* [[:workspace]])
            (let [ids (set/union (manage-section-on-search :basics term)
                                 (manage-section-on-search :workspace term)
                                 (manage-section-on-search :dashboard term)
                                 (manage-section-on-search :viewer term))]
              (reset! open-sections* ids))))

        on-search-term-change-2
        (mf/use-callback
         (fn [value]
           (manage-sections-on-search value)
           (reset! filter-term* value)))

        on-search-clear-click
        (mf/use-callback
         (fn [_]
           (reset! open-sections* [[:workspace]])
           (reset! filter-term* "")))

        go-to-edit-shortcuts
        (mf/use-fn
         #(st/emit! (rt/nav :settings-shortcuts {} {::rt/new-window true})))]

    [:div {:class (dm/str class " " (stl/css :shortcuts))}
     [:> panel-title* {:class (stl/css :shortcuts-title)
                       :text (tr "shortcuts.title")
                       :on-close close-fn}]

     [:div {:class (stl/css :search-field)}
      [:> search-bar* {:on-change on-search-term-change-2
                       :on-clear on-search-clear-click
                       :value filter-term
                       :placeholder (tr "shortcuts.title")
                       :icon-id i/search
                       :auto-focus true}]]

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
                                      :custom-shortcuts custom-shortcuts}]))]
       [:div {:class (stl/css :not-found)} (tr "shortcuts.not-found")])
     (when (contains? cf/flags :custom-shortcuts)
       [:> bl/button-link* {:on-click go-to-edit-shortcuts
                            :label (tr "shortcuts.edit-on-settings")
                            :class (stl/css :edit-shortcuts-button)
                            :icon (mf/html
                                   [:> icon* {:icon-id i/open-link
                                              :size "m"
                                              :aria-hidden true}])}])]))
