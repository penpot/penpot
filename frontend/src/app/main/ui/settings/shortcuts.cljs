(ns app.main.ui.settings.shortcuts
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.i18n :refer [tr]]
   [app.main.data.dashboard.shortcuts]
   [app.main.data.modal :as modal]
   [app.main.data.shortcuts :as ds]
   [app.main.data.viewer.shortcuts]
   [app.main.data.workspace.path.shortcuts]
   [app.main.data.workspace.shortcuts]
   [app.main.data.workspace.shortcuts.customize :as customize]
   [app.main.store :as st]
   [app.main.ui.components.search-bar :refer [search-bar*]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*] :as i]
   [app.main.ui.ds.foundations.typography :as t]
   [app.main.ui.ds.foundations.typography.heading :refer [heading*]]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.shortcuts :as ss]
   [app.util.dom :as dom]
   [app.util.strings :refer [matches-search]]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private workspace-shortcuts-raw
  (d/deep-merge app.main.data.workspace.path.shortcuts/shortcuts
                app.main.data.workspace.shortcuts/shortcuts))

(mf/defc search-section*
  [{:keys [filter-term on-search-term-change on-search-clear-click on-restore-all show-restore-all? has-custom-shortcuts]}]
  [:div {:class (stl/css :shortcuts-search-section)}
   [:> search-bar* {:on-change on-search-term-change
                    :on-clear on-search-clear-click
                    :value filter-term
                    :placeholder (tr "shortcuts.title")
                    :icon-id i/search
                    :auto-focus true}]
   (when (and show-restore-all? has-custom-shortcuts)
     [:> button*
      {:variant "secondary"
       :on-click on-restore-all
       :class (stl/css :restore-all-button)
       :icon i/at}
      "Restore all"])])

(mf/defc restore-all-modal
  {::mf/register modal/components
   ::mf/register-as :restore-all-modal}
  [{:keys [custom-shortcuts]}]
  (let [handle-close-dialog (mf/use-fn
                             (mf/deps)
                             (fn [event]
                               (dom/stop-propagation event)
                               (st/emit! (modal/hide))))

        handle-accept-dialog (mf/use-fn
                              (mf/deps)
                              (fn [event]
                                (dom/stop-propagation event)
                                (st/emit! (customize/reset-all-custom-shortcuts))
                                (st/emit! (modal/hide))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog)}
      [:> icon-button* {:class (stl/css :close-btn)
                        :variant "ghost"
                        :aria-label  (tr "labels.close")
                        :on-click handle-close-dialog
                        :icon i/close}]
      [:div {:class (stl/css :modal-title)}
       "Restore default configuration"]

      [:div {:class (stl/css :modal-content)}
       [:div {:class (stl/css :modal-content-text)}
        "The following shortcuts will be restored to their default values."]

       [:table {:class (stl/css :shortcuts-table)}
        [:thead
         [:tr {:class (stl/css :shortcuts-list-header)}
          [:th {:class (stl/css :shortcut-header-name)}
           "Shortcut name"]
          [:th {:class (stl/css :shortcut-header-command)}
           "Current"]
          [:th {:class (stl/css :shortcut-header-command)}
           "Default"]]]
        [:tbody {:class (stl/css :shortcuts-list-body)}
         (for [shortcut-key (keys custom-shortcuts)]
           (let [default-command (:command (get workspace-shortcuts-raw shortcut-key))

                 default-managed-list    (if (coll? default-command)
                                           default-command
                                           (conj () default-command))

                 default-chars-list      (map ds/split-sc default-managed-list)

                 default-last-element    (last default-chars-list)

                 default-short-char-list (if (= 1 (count default-chars-list))
                                           default-chars-list
                                           (drop-last default-chars-list))

                 default-penultimate     (last default-short-char-list)

                 current-command (or (get custom-shortcuts shortcut-key) default-command)
                 current-managed-list    (if (coll? current-command)
                                           current-command
                                           (conj () current-command))

                 current-chars-list      (map ds/split-sc current-managed-list)

                 current-last-element    (last current-chars-list)

                 current-short-char-list (if (= 1 (count current-chars-list))
                                           current-chars-list
                                           (drop-last current-chars-list))

                 current-penultimate     (last current-short-char-list)]
             [:tr {:key (name shortcut-key)
                   :class (stl/css :shortcuts-list-item)}
              [:td {:class (stl/css :shortcut-name)}
               (ss/translation-keyname :sc shortcut-key)]
              [:td {:class (stl/css :shortcut-command)}
               (for [chars current-short-char-list]
                 [:* {:key (str/join chars)}
                  (for [char chars]
                    [:> ss/converted-chars* {:key (dm/str char "-" (name shortcut-key))
                                             :char char
                                             :class (stl/css :default-command)
                                             :command shortcut-key}])
                  (when (not= chars current-penultimate) [:span {:class (stl/css :space)} ","])])
               (when (not= current-last-element current-penultimate)
                 [:*
                  [:span {:class (stl/css :space)} (tr "shortcuts.or")]
                  (for [char current-last-element]
                    [:> ss/converted-chars* {:key (dm/str char "-" (name shortcut-key))
                                             :char char
                                             :class (stl/css :default-command)
                                             :command shortcut-key}])])]

              [:td {:class (stl/css :shortcut-command)}
               (for [chars default-short-char-list]
                 [:* {:key (str/join chars)}
                  (for [char chars]
                    [:> ss/converted-chars* {:key (dm/str char "-" (name shortcut-key))
                                             :class (stl/css :default-command)
                                             :char char
                                             :command shortcut-key}])
                  (when (not= chars default-penultimate) [:span {:class (stl/css :space)} ","])])
               (when (not= default-last-element default-penultimate)
                 [:*
                  [:span {:class (stl/css :space)} (tr "shortcuts.or")]
                  (for [char default-last-element]
                    [:> ss/converted-chars* {:key (dm/str char "-" (name shortcut-key))
                                             :class (stl/css :default-command)
                                             :char char
                                             :command shortcut-key}])])]]))]]]

      [:div {:class (stl/css :modal-footer)}
       [:div {:class (stl/css :action-buttons)}
        [:> button* {:class (stl/css :cancel-button)
                     :variant "secondary"
                     :type "button"
                     :on-click modal/hide!}
         (tr "labels.cancel")]
        [:> button* {:class (stl/css :cancel-button)
                     :type "button"
                     :variant "primary"
                     :on-click handle-accept-dialog}
         "restore all"]]]]]))


(defn- filter-shortcuts-tree
  [tree shortcut-filter search-term]
  (into {}
        (keep (fn [[k node]]
                (let [children (:children node)]
                  (cond
                    (map? children)
                    (let [filtered-children
                          (filter-shortcuts-tree children shortcut-filter search-term)]
                      (when (seq filtered-children)
                        [k (assoc node :children filtered-children)]))

                    (shortcut-filter k node search-term)
                    [k node]))))
        tree))

(defn- collect-open-section-ids
  [tree]
  (letfn [(walk [nodes]
            (mapcat
             (fn [node]
               (let [children (:children node)]
                 (concat
                  (when (:children node)
                    [(:id node)])
                  (when (map? children)
                    (walk (vals children))))))
             nodes))]
    (->> (walk (vals tree))
         (remove nil?)
         set)))

(mf/defc shortcuts-list*
  [{:keys [shortcuts all-shortcuts all-sc-raw open-sections filter-term custom-shortcuts editable? manage-sections on-reset]}]
  (let [sections (keep (fn [section]
                         (when (seq (get-in section [1 :children]))
                           section))
                       shortcuts)]
    [:div {:class (stl/css :shortcuts-list)}
     (for [section sections]
       (let [[section-key _] section]
         [:> ss/shortcut-section* {:key (name section-key)
                                   :section section
                                   :all-sc-raw all-sc-raw
                                   :manage-sections manage-sections
                                   :open-sections open-sections
                                   :filter-term filter-term
                                   :editable? editable?
                                   :custom-shortcuts custom-shortcuts
                                   :all-shortcuts all-shortcuts
                                   :on-reset on-reset}]))]))

(mf/defc shortcuts-tab-section*
  [{:keys [profile all-shortcuts all-sc-raw shortcut-filter show-restore-all? on-restore-all]}]
  (let [open-sections*   (mf/use-state [[1]])
        filter-term*     (mf/use-state "")
        custom-shortcuts (get-in profile [:props :custom-shortcuts])

        section-has-content?
        (fn [section]
          (let [children (:children section)]
            (if (and (= (count children) 1) (contains? children :none))
              (seq (:children (:none children)))
              (seq children))))

        all-shortcuts (into {} (filter (fn [[_ v]] (section-has-content? v)) all-shortcuts))
        filtered-shortcuts (filter-shortcuts-tree all-shortcuts shortcut-filter @filter-term*)
        search-open-sections (collect-open-section-ids filtered-shortcuts)
        effective-open-sections (if (str/blank? @filter-term*)
                                  @open-sections*
                                  search-open-sections)

        on-search-term-change
        (mf/use-fn
         (mf/deps all-shortcuts shortcut-filter)
         (fn [term]
           (reset! filter-term* term)
            (if (str/blank? term)
              (reset! open-sections* [[1]])
              (let [filtered-tree (filter-shortcuts-tree all-shortcuts shortcut-filter term)
                    open-ids (collect-open-section-ids filtered-tree)]
                (reset! open-sections* open-ids)))))

        on-search-clear-click
        (mf/use-fn
         (fn [_]
           (reset! open-sections* [[1]])
           (reset! filter-term* "")))

        on-reset-shortcut
        (mf/use-fn
         (fn [shortcut-key]
           (st/emit! (customize/reset-custom-shortcut shortcut-key))))

        manage-sections
        (fn [item]
          (fn [event]
            (dom/stop-propagation event)
            (let [is-present? (some #(= % item) @open-sections*)
                  new-value (if is-present?
                              (filterv (fn [element] (not= element item)) @open-sections*)
                              (conj @open-sections* item))]
              (reset! open-sections* new-value))))]

    [:div {:class (stl/css :shortcuts-section)}
     [:> search-section* {:filter-term @filter-term*
                          :on-search-term-change on-search-term-change
                          :on-search-clear-click on-search-clear-click
                          :on-restore-all on-restore-all
                          :show-restore-all? show-restore-all?
                          :has-custom-shortcuts (seq custom-shortcuts)}]
     [:> shortcuts-list* {:shortcuts filtered-shortcuts
                          :all-shortcuts all-shortcuts
                          :all-sc-raw all-sc-raw
                          :open-sections effective-open-sections
                          :filter-term filter-term*
                          :custom-shortcuts custom-shortcuts
                          :editable? true
                          :manage-sections manage-sections
                          :on-reset on-reset-shortcut}]]))

(mf/defc all-shortcuts-section*
  [{:keys [profile all-shortcuts all-sc-raw on-restore-all]}]
  [:> shortcuts-tab-section* {:profile profile
                              :all-shortcuts all-shortcuts
                              :all-sc-raw all-sc-raw
                              :shortcut-filter (fn [_ shortcut search-term]
                                                 (or (str/blank? search-term)
                                                     (matches-search (:translation shortcut) search-term)))
                              :show-restore-all? true
                              :on-restore-all on-restore-all}])

(mf/defc personalized-shortcuts-section*
  [{:keys [profile all-shortcuts all-sc-raw on-restore-all]}]
  (let [custom-shortcuts (get-in profile [:props :custom-shortcuts])]
    (if (seq custom-shortcuts)
      [:> shortcuts-tab-section* {:profile profile
                                  :all-shortcuts all-shortcuts
                                  :all-sc-raw all-sc-raw
                                  :shortcut-filter (fn [shortcut-key shortcut search-term]
                                                     (and (contains? custom-shortcuts shortcut-key)
                                                          (or (str/blank? search-term)
                                                              (matches-search (:translation shortcut) search-term))))
                                  :show-restore-all? true
                                  :on-restore-all on-restore-all}]
      [:div {:class (stl/css :shortcuts-section)}
       [:p "There are no personalized shortcuts."]])))

(mf/defc not-assigned-shortcuts-section*
  [{:keys [profile all-shortcuts all-sc-raw on-restore-all]}]
  [:> shortcuts-tab-section* {:profile profile
                              :all-shortcuts all-shortcuts
                              :all-sc-raw all-sc-raw
                              :shortcut-filter (fn [shortcut-key shortcut search-term]
                                                 (and (not (seq (:command shortcut)))
                                                      (not (contains? (get-in profile [:props :custom-shortcuts]) shortcut-key))
                                                      (or (str/blank? search-term)
                                                          (matches-search (:translation shortcut) search-term))))
                              :show-restore-all? true
                              :on-restore-all on-restore-all}])


(mf/defc shortcuts-page*
  [{:keys [profile]}]
  (let [section*        (mf/use-state :all)
        section         (deref section*)

        tabs
        (mf/with-memo []
          [{:label "All"
            :id "all"}
           {:label "Personalized"
            :data-testid "personalized"
            :id "personalized"}
           {:label "Not assigned"
            :data-testid "not-assigned"
            :id "not-assigned"}])

        handle-change-tab
        (mf/use-fn
         (mf/deps)
         (fn [new-section]
           (reset! section* (keyword new-section))))
        custom-shortcuts           (get-in profile [:props :custom-shortcuts])

        workspace-shortcuts-custom (ds/apply-custom-overrides workspace-shortcuts-raw custom-shortcuts)

        workspace-shortcuts        (->> workspace-shortcuts-custom
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
        
        all-shortcuts-raw (merge workspace-shortcuts-custom dashboard-shortcuts-custom viewer-shortcuts-custom)

        {:keys [all-shortcuts all-sc-names all-sub-names all-section-names]}
        (ss/build-all-shortcuts workspace-shortcuts dashboard-shortcuts viewer-shortcuts)
        _ (.log js/console "all-shortcuts" (clj->js all-shortcuts))

        all-item-names (concat all-sc-names all-sub-names all-section-names)


        on-restore-all
        (mf/use-fn
         (mf/deps)
         (fn [event]
           (dom/stop-propagation event)
           (st/emit! (modal/show :restore-all-modal
                                 {:type :restore-all-modal
                                  :custom-shortcuts custom-shortcuts}))))]

    [:section {:class (stl/css :shortcuts-page)
               :aria-label "Shortcuts page"}
     [:div {:class (stl/css :shortcuts-content)}
      [:> heading* {:level 1
                    :typography t/title-large
                    :class (stl/css :page-title)}
       (tr "label.shortcuts")]

      [:div {:class (stl/css :shortcuts-content)}


       [:> tab-switcher* {:tabs tabs
                          :selected (name section)
                          :on-change handle-change-tab
                          :class (stl/css :shortcuts-switcher)}
        (case section
          :all
          [:> all-shortcuts-section*
           {:profile profile
            :all-shortcuts all-shortcuts
            :all-sc-raw all-shortcuts-raw
            :all-item-names all-item-names
            :on-restore-all on-restore-all}]

          :personalized
          [:> personalized-shortcuts-section*
           {:profile profile
            :all-shortcuts all-shortcuts
            :all-sc-raw all-shortcuts-raw
            :all-item-names all-item-names
            :on-restore-all on-restore-all}]

          :not-assigned
          [:> not-assigned-shortcuts-section*
           {:profile profile
            :all-shortcuts all-shortcuts
            :all-sc-raw all-shortcuts-raw
            :all-item-names all-item-names
            :on-restore-all on-restore-all}])]
       [:div {:class (stl/css :shortcuts-page-footer)}
        [:div {:class (stl/css :shortcuts-info)}
         [:div {:class (stl/css :shortcuts-info-wrapper)}
          [:div {:class (stl/css :dot-wrapper)}
           [:div {:class (stl/css :shortcuts-customized-dot)}]]
          [:p {:class (stl/css :shortcuts-text)} "Personalized"]]
         
         [:div {:class (stl/css :shortcuts-info-wrapper)}
          [:div {:class (stl/css :dot-wrapper)}
           [:div {:class (stl/css :shortcuts-conflict-dot)}]]
          [:p {:class (stl/css :shortcuts-text)} "Conflict"]]
         
         [:div {:class (stl/css :shortcuts-info-wrapper)}
          [:> icon* {:class (stl/css :shortcuts-not-assigned-icon)
                     :icon-id i/detach}]
          [:p {:class (stl/css :shortcuts-text)} "Not assigned"]]]

        [:> button* {:variant "secondary"
                     :on-click #()
                     :icon i/import-export
                     :icon-size "m"}
         "Export/Import"]]]]]))
