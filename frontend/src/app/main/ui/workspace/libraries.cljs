;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.libraries
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.colors-list :as ctcl]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.common.types.typographies-list :as ctyl]
   [app.common.uuid :as uuid]
   [app.main.data.dashboard :as dd]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.data.profile :as du]
   [app.main.data.team :as dtm]
   [app.main.data.workspace.colors :as mdc]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.refs :as refs]
   [app.main.render :refer [component-svg]]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.components.link-button :as lb]
   [app.main.ui.components.search-bar :refer [search-bar]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.layout.tab-switcher :refer [tab-switcher*]]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.strings :refer [matches-search]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private close-icon
  (i/icon-xref :close (stl/css :close-icon)))

(def ^:private add-icon
  (i/icon-xref :add (stl/css :add-icon)))

(def ^:private detach-icon
  (i/icon-xref :detach (stl/css :detach-icon)))

(def ^:private library-icon
  (i/icon-xref :library (stl/css :library-icon)))

(defn- get-library-summary
  "Given a library data return a summary representation of this library"
  [data]
  (let [colors       (count (:colors data))
        graphics     0
        typographies (count (:typographies data))
        components   (count (ctkl/components-seq data))
        empty?       (and (zero? components)
                          (zero? graphics)
                          (zero? colors)
                          (zero? typographies))]

    {:is-empty empty?
     :colors colors
     :graphics graphics
     :typographies typographies
     :components components}))

(defn- adapt-backend-summary
  [summary]
  (let [components   (or (-> summary :components :count) 0)
        graphics     (or (-> summary :media :count) 0)
        typographies (or (-> summary :typographies :count) 0)
        colors       (or (-> summary :colors :count) 0)

        empty?       (and (zero? components)
                          (zero? graphics)
                          (zero? colors)
                          (zero? typographies))]
    {:is-empty     empty?
     :components   components
     :graphics     graphics
     :typographies typographies
     :colors       colors}))

(defn- describe-library
  [components-count graphics-count colors-count typography-count]
  (let [all-zero? (and (zero? components-count)
                       (zero? graphics-count)
                       (zero? colors-count)
                       (zero? typography-count))]
    (str
     (str/join " · "
               (cond-> []
                 (or all-zero? (pos? components-count))
                 (conj (tr "workspace.libraries.components" components-count))

                 (or all-zero? (pos? graphics-count))
                 (conj (tr "workspace.libraries.graphics" graphics-count))

                 (or all-zero? (pos? colors-count))
                 (conj (tr "workspace.libraries.colors" colors-count))

                 (or all-zero? (pos? typography-count))
                 (conj (tr "workspace.libraries.typography" typography-count))))
     "\u00A0")))

(mf/defc library-description*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [summary]}]
  (let [components-count (get summary :components)
        graphics-count   (get summary :graphics)
        typography-count (get summary :typographies)
        colors-count     (get summary :colors)]

    [:*
     (when (pos? components-count)
       [:li {:class (stl/css :element-count)}
        (tr "workspace.libraries.components" components-count)])

     (when (pos? graphics-count)
       [:li {:class (stl/css :element-count)}
        (tr "workspace.libraries.graphics" graphics-count)])

     (when (pos? colors-count)
       [:li {:class (stl/css :element-count)}
        (tr "workspace.libraries.colors" colors-count)])

     (when (pos? typography-count)
       [:li {:class (stl/css :element-count)}
        (tr "workspace.libraries.typography" typography-count)])]))

(mf/defc sample-library-entry*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [library importing]}]
  (let [id         (:id library)
        importing? (deref importing)

        team-id    (mf/use-ctx ctx/current-team-id)

        on-error
        (mf/use-fn
         (fn [_]
           (reset! importing nil)
           (rx/of (ntf/error (tr "dashboard.libraries-and-templates.import-error")))))

        on-success
        (mf/use-fn
         (mf/deps team-id)
         (fn [_]
           (st/emit! (dtm/fetch-shared-files team-id))))

        import-library
        (mf/use-fn
         (mf/deps on-success on-error)
         (fn [_]
           (reset! importing id)
           (st/emit! (dd/clone-template
                      (with-meta {:template-id id}
                        {:on-success on-success
                         :on-error on-error})))))]

    [:div {:class (stl/css :sample-library-item)
           :key (dm/str id)}
     [:div {:class (stl/css :sample-library-item-name)} (:name library)]
     [:input {:class (stl/css-case :sample-library-button true
                                   :sample-library-add (nil? importing?)
                                   :sample-library-adding (some? importing?))
              :type "button"
              :value (if (= importing? id) (tr "labels.adding") (tr "labels.add"))
              :on-click import-library}]]))

(defn- empty-library?
  "Check if currentt library summary has elements or not"
  [summary]
  (boolean (:is-empty summary)))

(mf/defc libraries-tab*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [is-shared linked-libraries shared-libraries]}]
  (let [file-id        (mf/use-ctx ctx/current-file-id)
        search-term*   (mf/use-state "")
        search-term    (deref search-term*)

        ;; The summary of the current/local library
        ;; NOTE: we only need a snapshot of current library
        local-library  (deref refs/workspace-data)
        summary        (get-library-summary local-library)
        empty-library? (empty-library? summary)

        selected       (h/use-shared-state mdc/colorpalette-selected-broadcast-key :recent)


        shared-libraries
        (mf/with-memo [shared-libraries linked-libraries file-id search-term]
          (when shared-libraries
            (->> (vals shared-libraries)
                 (remove #(= (:id %) file-id))
                 (remove #(contains? linked-libraries (:id %)))
                 (filter #(matches-search (:name %) search-term))
                 (sort-by (comp str/lower :name)))))

        linked-libraries
        (mf/with-memo [linked-libraries]
          (->> (vals linked-libraries)
               (sort-by (comp str/lower :name))))

        importing*       (mf/use-state nil)
        sample-libraries [{:id "penpot-design-system", :name "Design system example"}
                          {:id "wireframing-kit", :name "Wireframe library"}
                          {:id "whiteboarding-kit", :name "Whiteboarding Kit"}]

        change-search-term
        (mf/use-fn
         (fn [event]
           (reset! search-term* event)))

        link-library
        (mf/use-fn
         (mf/deps file-id)
         (fn [event]
           (let [library-id (some-> (dom/get-current-target event)
                                    (dom/get-data "library-id")
                                    (parse-uuid))]
             (reset! selected library-id)
             (st/emit! (dwl/link-file-to-library file-id library-id)))))

        unlink-library
        (mf/use-fn
         (mf/deps file-id)
         (fn [event]
           (let [library-id (some-> (dom/get-current-target event)
                                    (dom/get-data "library-id")
                                    (parse-uuid))]
             (when (= library-id @selected)
               (reset! selected :file))
             (st/emit! (dwl/unlink-file-from-library file-id library-id)
                       (dwl/sync-file file-id library-id)))))

        on-delete-accept
        (mf/use-fn
         (mf/deps file-id)
         #(st/emit! (dwl/set-file-shared file-id false)
                    (modal/show :libraries-dialog {:file-id file-id})))

        on-delete-cancel
        (mf/use-fn
         (mf/deps file-id)
         #(st/emit! (modal/show :libraries-dialog {:file-id file-id})))

        publish
        (mf/use-fn
         (mf/deps file-id)
         (fn [event]
           (let [input-node (dom/get-target event)
                 publish-library #(st/emit! (dwl/set-file-shared file-id true))
                 cancel-publish #(st/emit! (modal/show :libraries-dialog {:file-id file-id}))]
             (if empty-library?
               (st/emit! (modal/show
                          {:type :confirm
                           :title (tr "modals.publish-empty-library.title")
                           :message (tr "modals.publish-empty-library.message")
                           :accept-label (tr "modals.publish-empty-library.accept")
                           :on-accept publish-library
                           :on-cancel cancel-publish}))
               (publish-library))
             (dom/blur! input-node))))

        unpublish
        (mf/use-fn
         (mf/deps file-id)
         (fn [_]
           (st/emit! (modal/show
                      {:type :delete-shared-libraries
                       :ids #{file-id}
                       :origin :unpublish
                       :on-accept on-delete-accept
                       :on-cancel on-delete-cancel
                       :count-libraries 1}))))]

    [:div {:class (stl/css :libraries-content)}
     [:div {:class (stl/css :lib-section)}
      [:& title-bar {:collapsable false
                     :title       (tr "workspace.libraries.in-this-file")
                     :class       (stl/css :title-spacing-lib)}]
      [:div {:class (stl/css :section-list)}

       [:div {:class (stl/css :section-list-item)}
        [:div {:class (stl/css :item-content)}
         [:div {:class (stl/css :item-name)} (tr "workspace.libraries.file-library")]
         [:ul {:class (stl/css :item-contents)}
          [:> library-description* {:summary summary}]]]

        (if ^boolean is-shared
          [:input {:class (stl/css :item-unpublish)
                   :type "button"
                   :value (tr "common.unpublish")
                   :on-click unpublish}]
          [:input {:class (stl/css :item-publish)
                   :type "button"
                   :value (tr "common.publish")
                   :on-click publish}])]

       (for [{:keys [id name data] :as library} linked-libraries]
         [:div {:class (stl/css :section-list-item)
                :key (dm/str id)
                :data-testid "library-item"}
          [:div {:class (stl/css :item-content)}
           [:div {:class (stl/css :item-name)} name]
           [:ul {:class (stl/css :item-contents)}
            (let [summary (get-library-summary data)]
              [:> library-description* {:summary summary}])]]

          [:button {:class (stl/css :item-button)
                    :type "button"
                    :title (tr "workspace.libraries.unlink-library-btn")
                    :data-library-id (dm/str id)
                    :on-click unlink-library}
           detach-icon]])]]

     [:div {:class (stl/css :shared-section)}
      [:& title-bar {:collapsable false
                     :title       (tr "workspace.libraries.shared-libraries")
                     :class       (stl/css :title-spacing-lib)}]
      [:& search-bar {:on-change change-search-term
                      :value search-term
                      :placeholder (tr "workspace.libraries.search-shared-libraries")
                      :icon (mf/html [:span {:class (stl/css :search-icon)} i/search])}]

      (if (seq shared-libraries)
        [:div {:class (stl/css :section-list-shared)}
         (for [{:keys [id name] :as library} shared-libraries]
           [:div {:class (stl/css :section-list-item)
                  :key (dm/str id)
                  :data-testid "library-item"}
            [:div {:class (stl/css :item-content)}
             [:div {:class (stl/css :item-name)} name]
             [:ul {:class (stl/css :item-contents)}
              (let [summary (-> (:library-summary library)
                                (adapt-backend-summary))]
                [:> library-description* {:summary summary}])]]

            [:button {:class (stl/css :item-button-shared)
                      :data-library-id (dm/str id)
                      :title (tr "workspace.libraries.shared-library-btn")
                      :on-click link-library}
             add-icon]])]

        (when (empty? shared-libraries)
          [:div {:class (stl/css :section-list-empty)}
           (cond
             (nil? shared-libraries)
             (tr "workspace.libraries.loading")

             (str/empty? search-term)
             [:*
              [:div {:class (stl/css :sample-libraries-info)}
               (tr "workspace.libraries.empty.no-libraries")
               [:a {:target "_blank"
                    :class (stl/css :sample-libraries-link)
                    :href "https://penpot.app/libraries-templates"}
                (tr "workspace.libraries.empty.some-templates")]]
              [:div {:class (stl/css :sample-libraries-container)}
               (tr "workspace.libraries.empty.add-some")
               (for [library sample-libraries]
                 [:> sample-library-entry*
                  {:library library
                   :importing importing*}])]]

             :else
             (tr "workspace.libraries.no-matches-for" search-term))]))]]))

(defn- extract-assets
  [file-data library summary?]
  (let [exceeded (volatile! {:components false
                             :colors false
                             :typographies false})

        truncate (fn [asset-type items]
                   (if (and summary? (> (count items) 5))
                     (do
                       (vswap! exceeded assoc asset-type true)
                       (take 5 items))
                     items))

        assets (dwl/assets-need-sync library file-data)

        component-ids  (into #{} (->> assets
                                      (filter #(= (:asset-type %) :component))
                                      (map :asset-id)))
        color-ids      (into #{} (->> assets
                                      (filter #(= (:asset-type %) :color))
                                      (map :asset-id)))
        typography-ids (into #{} (->> assets
                                      (filter #(= (:asset-type %) :typography))
                                      (map :asset-id)))

        components   (->> component-ids
                          (map #(ctkl/get-component (:data library) %))
                          (sort-by #(str/lower (:name %)))
                          (truncate :components))
        colors       (->> color-ids
                          (map #(ctcl/get-color (:data library) %))
                          (sort-by #(str/lower (:name %)))
                          (truncate :colors))
        typographies (->> typography-ids
                          (map #(ctyl/get-typography (:data library) %))
                          (sort-by #(str/lower (:name %)))
                          (truncate :typographies))]

    [library @exceeded {:components components
                        :colors colors
                        :typographies typographies}]))

(mf/defc updates-tab*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [file-id libraries]}]
  ;; FIXME: naming
  (let [summary?*  (mf/use-state true)
        summary?   (deref summary?*)
        updating?  (mf/deref refs/updating-library)

        ;; NOTE: we don't want to react on file changes, we just want
        ;; a snapshot of file on the momento of open the dialog
        file-data  (deref refs/workspace-data)

        see-all-assets
        (mf/use-fn
         (fn []
           (reset! summary?* false)))

        libs-assets (mf/with-memo [file-data libraries summary?*]
                      (->> (vals libraries)
                           (map #(extract-assets file-data % summary?))
                           (filter (fn [[_ _ {:keys [components colors typographies]}]]
                                     (or (seq components)
                                         (seq colors)
                                         (seq typographies))))))

        update         (mf/use-fn
                        (mf/deps file-id)
                        (fn [event]
                          (when-not updating?
                            (let [library-id (some-> (dom/get-target event)
                                                     (dom/get-data "library-id")
                                                     (parse-uuid))]
                              (st/emit!
                               (dwl/set-updating-library true)
                               (dwl/sync-file file-id library-id))))))]

    [:div {:class (stl/css :updates-content)}
     [:div {:class (stl/css :update-section)}
      (if (empty? libs-assets)
        [:div {:class (stl/css :section-list-empty)}
         [:span {:class (stl/css :empty-state-icon)}
          library-icon]
         (tr "workspace.libraries.no-libraries-need-sync")]
        [:*
         [:div {:class (stl/css :section-title)} (tr "workspace.libraries.library-updates")]

         [:div {:class (stl/css :section-list)}
          (for [[{:keys [id name] :as library}
                 exceeded
                 {:keys [components colors typographies]}] libs-assets]
            [:div {:class (stl/css :section-list-item)
                   :key (dm/str id)}
             [:div {:class (stl/css :item-content)}
              [:div {:class (stl/css :item-name)} name]
              [:ul {:class (stl/css :item-contents)} (describe-library
                                                      (count components)
                                                      0
                                                      (count colors)
                                                      (count typographies))]]
             [:button {:type "button"
                       :class (stl/css :item-update)
                       :disabled updating?
                       :data-library-id (dm/str id)
                       :on-click update}
              (tr "workspace.libraries.update")]

             [:div {:class (stl/css :libraries-updates)}
              (when-not (empty? components)
                [:div {:class (stl/css :libraries-updates-column)}
                 (for [component components]
                   [:div {:class (stl/css :libraries-updates-item)
                          :key (dm/str (:id component))}
                    (let [component (ctf/load-component-objects (:data library) component)
                          root-shape (ctf/get-component-root (:data library) component)]
                      [:*
                       [:& component-svg {:root-shape root-shape
                                          :class (stl/css :component-svg)
                                          :objects (:objects component)}]
                       [:div {:class (stl/css :name-block)}
                        [:span {:class (stl/css :item-name)
                                :title (:name component)}
                         (:name component)]]])])
                 (when (:components exceeded)
                   [:div {:class (stl/css :libraries-updates-item)
                          :key (uuid/next)}
                    [:div {:class (stl/css :name-block :ellipsis)}
                     [:span {:class (stl/css :item-name)} "(...)"]]])])

              (when-not (empty? colors)
                [:div {:class (stl/css :libraries-updates-column)
                       :style #js {"--bullet-size" "24px"}}
                 (for [color colors]
                   (let [default-name (cond
                                        (:gradient color) (uc/gradient-type->string (get-in color [:gradient :type]))
                                        (:color color) (:color color)
                                        :else (:value color))]
                     [:div {:class (stl/css :libraries-updates-item)
                            :key (dm/str (:id color))}
                      [:*
                       [:& cb/color-bullet {:color {:color (:color color)
                                                    :id (:id color)
                                                    :opacity (:opacity color)}}]
                       [:div {:class (stl/css :name-block)}
                        [:span {:class (stl/css :item-name)
                                :title (:name color)}
                         (:name color)]
                        (when-not (= (:name color) default-name)
                          [:span.color-value (:color color)])]]]))
                 (when (:colors exceeded)
                   [:div {:class (stl/css :libraries-updates-item)
                          :key (uuid/next)}
                    [:div {:class (stl/css :name-block.ellipsis)}
                     [:span {:class (stl/css :item-name)} "(...)"]]])])

              (when-not (empty? typographies)
                [:div {:class (stl/css :libraries-updates-column)}
                 (for [typography typographies]
                   [:div {:class (stl/css :libraries-updates-item)
                          :key (dm/str (:id typography))}
                    [:*
                     [:div {:style {:font-family (:font-family typography)
                                    :font-weight (:font-weight typography)
                                    :font-style (:font-style typography)}}
                      (tr "workspace.assets.typography.sample")]
                     [:div {:class (stl/css :name-block)}
                      [:span {:class (stl/css :item-name)
                              :title (:name typography)}
                       (:name typography)]]]])
                 (when (:typographies exceeded)
                   [:div {:class (stl/css :libraries-updates-item)
                          :key (uuid/next)}
                    [:div {:class (stl/css :name-block.ellipsis)}
                     [:span {:class (stl/css :item-name)} "(...)"]]])])]

             (when (or (pos? (:components exceeded))
                       (pos? (:colors exceeded))
                       (pos? (:typographies exceeded)))
               [:& lb/link-button
                {:on-click see-all-assets
                 :class (stl/css :libraries-updates-see-all)
                 :value (str "(" (tr "workspace.libraries.update.see-all-changes") ")")}])])]])]]))

(mf/defc libraries-dialog
  {::mf/register modal/components
   ::mf/register-as :libraries-dialog}
  [{:keys [starting-tab file-id] :as props :or {starting-tab :libraries}}]
  (let [files   (mf/deref refs/files)
        file    (get files file-id)
        team-id (:team-id file)
        shared? (:is-shared file)

        linked-libraries
        (mf/with-memo [files file-id]
          (refs/select-libraries files file-id))

        linked-libraries
        (mf/with-memo [linked-libraries file-id]
          (d/removem (fn [[_ lib]]
                       (or (:is-indirect lib)
                           (= (:id lib) file-id)))
                     linked-libraries))

        shared-libraries
        (mf/deref refs/shared-files)

        close-dialog-outside
        (mf/use-fn
         (fn [event]
           (when (= (dom/get-target event) (dom/get-current-target event))
             (modal/hide!))))

        close-dialog
        (mf/use-fn
         (fn [_]
           (modal/hide!)
           (modal/disallow-click-outside!)))

        libraries-tab
        (mf/html [:> libraries-tab*
                  {:is-shared shared?
                   :linked-libraries linked-libraries
                   :shared-libraries shared-libraries}])

        updates-tab
        (mf/html [:> updates-tab*
                  {:file-id file-id
                   :libraries linked-libraries}])

        tabs
        #js [#js {:label (tr "workspace.libraries.libraries")
                  :id "libraries"
                  :content libraries-tab}
             #js {:label (tr "workspace.libraries.updates")
                  :id "updates"
                  :content updates-tab}]]

    (mf/with-effect [team-id]
      (st/emit! (dtm/fetch-shared-files team-id)))

    [:div {:class (stl/css :modal-overlay)
           :on-click close-dialog-outside
           :data-testid "libraries-modal"}
     [:div {:class (stl/css :modal-dialog)}
      [:button {:class (stl/css :close-btn)
                :on-click close-dialog
                :aria-label (tr "labels.close")
                :data-testid "close-libraries"}
       close-icon]
      [:div {:class (stl/css :modal-title)}
       (tr "workspace.libraries.libraries")]

      [:> tab-switcher* {:tabs tabs
                         :default-selected (dm/str starting-tab)}]]]))

(mf/defc v2-info-dialog
  {::mf/register modal/components
   ::mf/register-as :v2-info}
  []
  (let [handle-gotit-click
        (mf/use-fn
         (fn []
           (modal/hide!)
           (st/emit! (du/update-profile-props {:v2-info-shown true}))))]

    [:div {:class (stl/css :modal-overlay)}
     [:div {:class (stl/css :modal-dialog :modal-v2-info)}
      [:div {:class (stl/css :modal-v2-title)}
       "IMPORTANT INFORMATION ABOUT NEW COMPONENTS"]
      [:div  {:class (stl/css :modal-content)}
       [:div {:class (stl/css :info-content)}
        [:div {:class (stl/css :info-block)}
         [:div {:class (stl/css :info-icon)} i/v2-icon-1]
         [:div {:class (stl/css :info-block-title)}
          "One physical source of truth"]
         [:div {:class (stl/css :info-block-content)}
          "Main components are now found at the design space. They act as a single source "
          "of truth and can be worked on with their copies. This ensures consistency and "
          "allows better control and synchronization."]]

        [:div {:class (stl/css :info-block)}
         [:div {:class (stl/css :info-icon)} i/v2-icon-2]
         [:div {:class (stl/css :info-block-title)}
          "Swap components"]
         [:div {:class (stl/css :info-block-content)}
          "Now, you can replace one component copy with another within your libraries. "
          "The swap components functionality streamlines making changes, testing "
          "variations, or updating elements without extensive manual adjustments."]]

        [:div {:class (stl/css :info-block)}
         [:div {:class (stl/css :info-icon)} i/v2-icon-3]
         [:div {:class (stl/css :info-block-title)}
          "Graphic assets no longer exist"]
         [:div {:class (stl/css :info-block-content)}
          "Graphic assets now disappear, so that all graphic assets become components. "
          "This way, swapping between them is possible, and we avoid confusion about "
          "what should go in each typology."]]

        [:div {:class (stl/css :info-block)}
         [:div {:class (stl/css :info-icon)} i/v2-icon-4]
         [:div {:class (stl/css :info-block-title)}
          "Main components page"]
         [:div {:class (stl/css :info-block-content)}
          "You might find that a new page called 'Main components' has appeared in "
          "your file. On that page, you'll find all the main components that were "
          "created in your files previously to this new version."]]]

       [:div {:class (stl/css :info-bottom)}
        [:button {:class (stl/css :primary-button)
                  :on-click handle-gotit-click} "I GOT IT"]]]]]))
