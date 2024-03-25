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
   [app.main.data.modal :as modal]
   [app.main.data.users :as du]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.refs :as refs]
   [app.main.render :refer [component-svg]]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.components.link-button :as lb]
   [app.main.ui.components.search-bar :refer [search-bar]]
   [app.main.ui.components.tab-container :refer [tab-container tab-element]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.icons :as i]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.strings :refer [matches-search]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def ^:private close-icon
  (i/icon-xref :close (stl/css :close-icon)))

(def ^:private add-icon
  (i/icon-xref :add (stl/css :add-icon)))

(def ^:private detach-icon
  (i/icon-xref :detach (stl/css :detach-icon)))

(def ^:private library-icon
  (i/icon-xref :library (stl/css :library-icon)))

(def ref:workspace-file
  (l/derived :workspace-file st/state))

(defn create-file-library-ref
  [library-id]
  (letfn [(getter-fn [state]
            (let [fdata (let [{:keys [id] :as wfile} (:workspace-data state)]
                          (if (= id library-id)
                            wfile
                            (dm/get-in state [:workspace-libraries library-id :data])))]
              {:colors     (-> fdata :colors vals)
               :media      (-> fdata :media vals)
               :components (ctkl/components-seq fdata)
               :typographies (-> fdata :typographies vals)}))]
    (l/derived getter-fn st/state =)))

(defn- describe-library
  [components-count graphics-count colors-count typography-count]
  (let [all-zero? (and (zero? components-count) (zero? graphics-count) (zero? colors-count) (zero? typography-count))]
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

(mf/defc describe-library-blocks
  [{:keys [components-count graphics-count colors-count typography-count] :as props}]
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
      (tr "workspace.libraries.typography" typography-count)])])


(mf/defc libraries-tab
  {::mf/wrap-props false}
  [{:keys [file-id shared? linked-libraries shared-libraries]}]
  (let [search-term*   (mf/use-state "")
        search-term    (deref search-term*)
        library-ref    (mf/with-memo [file-id]
                         (create-file-library-ref file-id))
        library        (deref library-ref)
        colors         (:colors library)
        components     (:components library)
        media          (:media library)
        typographies   (:typographies library)

        empty-library? (and
                        (zero? (count colors))
                        (zero? (count components))
                        (zero? (count media))
                        (zero? (count typographies)))

        shared-libraries
        (mf/with-memo [shared-libraries linked-libraries file-id search-term]
          (when shared-libraries
            (->> shared-libraries
                 (remove #(= (:id %) file-id))
                 (remove #(contains? linked-libraries (:id %)))
                 (filter #(matches-search (:name %) search-term))
                 (sort-by (comp str/lower :name)))))

        linked-libraries
        (mf/with-memo [linked-libraries]
          (->> (vals linked-libraries)
               (sort-by (comp str/lower :name))))

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
             (st/emit! (dwl/link-file-to-library file-id library-id)))))

        unlink-library
        (mf/use-fn
         (mf/deps file-id)
         (fn [event]
           (let [library-id (some-> (dom/get-current-target event)
                                    (dom/get-data "library-id")
                                    (parse-uuid))]
             (st/emit! (dwl/unlink-file-from-library file-id library-id)
                       (dwl/sync-file file-id library-id)))))

        on-delete-accept
        (mf/use-fn
         (mf/deps file-id)
         #(st/emit! (dwl/set-file-shared file-id false)
                    (modal/show :libraries-dialog {})))

        on-delete-cancel
        (mf/use-fn #(st/emit! (modal/show :libraries-dialog {})))

        publish
        (mf/use-fn
         (mf/deps file-id)
         (fn [event]
           (let [input-node (dom/get-target event)
                 publish-library #(st/emit! (dwl/set-file-shared file-id true))
                 cancel-publish #(st/emit! (modal/show :libraries-dialog {}))]
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
          [:& describe-library-blocks {:components-count (count components)
                                       :graphics-count (count media)
                                       :colors-count (count colors)
                                       :typography-count (count typographies)}]]]
        (if ^boolean shared?
          [:input {:class (stl/css :item-unpublish)
                   :type "button"
                   :value (tr "common.unpublish")
                   :on-click unpublish}]
          [:input {:class (stl/css :item-publish)
                   :type "button"
                   :value (tr "common.publish")
                   :on-click publish}])]

       (for [{:keys [id name] :as library} linked-libraries]
         [:div {:class (stl/css :section-list-item)
                :key (dm/str id)}
          [:div {:class (stl/css :item-content)}
           [:div {:class (stl/css :item-name)} name]
           [:ul {:class (stl/css :item-contents)}
            (let [components-count (count (or (ctkl/components-seq (:data library)) []))
                  graphics-count   (count (dm/get-in library [:data :media] []))
                  colors-count     (count (dm/get-in library [:data :colors] []))
                  typography-count (count (dm/get-in library [:data :typographies] []))]
              [:& describe-library-blocks {:components-count components-count
                                           :graphics-count graphics-count
                                           :colors-count colors-count
                                           :typography-count typography-count}])]]

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
                  :key (dm/str id)}
            [:div {:class (stl/css :item-content)}
             [:div {:class (stl/css :item-name)} name]
             [:ul {:class (stl/css :item-contents)}
              (let [components-count (dm/get-in library [:library-summary :components :count] 0)
                    graphics-count   (dm/get-in library [:library-summary :media :count] 0)
                    colors-count     (dm/get-in library [:library-summary :colors :count] 0)
                    typography-count (dm/get-in library [:library-summary :typographies :count] 0)]
                [:& describe-library-blocks {:components-count components-count
                                             :graphics-count graphics-count
                                             :colors-count colors-count
                                             :typography-count typography-count}])]]
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
              [:span {:class (stl/css :empty-state-icon)}
               library-icon]
              (tr "workspace.libraries.no-shared-libraries-available")]

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

(mf/defc updates-tab
  {::mf/wrap-props false}
  [{:keys [file-id file-data libraries]}]
  (let [summary?*  (mf/use-state true)
        summary?   (deref summary?*)
        updating?  (mf/deref refs/updating-library)

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
               [:& lb/link-button {:on-click see-all-assets
                                   :class (stl/css :libraries-updates-see-all)
                                   :value (str "(" (tr "workspace.libraries.update.see-all-changes") ")")}])])]])]]))
(mf/defc libraries-dialog
  {::mf/register modal/components
   ::mf/register-as :libraries-dialog}
  [{:keys [starting-tab] :as props :or {starting-tab :libraries}}]
  (let [project        (mf/deref refs/workspace-project)
        file-data      (mf/deref refs/workspace-data)
        file           (mf/deref ref:workspace-file)

        team-id        (:team-id project)
        file-id        (:id file)
        shared?        (:is-shared file)

        selected-tab*  (mf/use-state starting-tab)
        selected-tab   (deref selected-tab*)

        libraries      (mf/deref refs/workspace-libraries)
        libraries      (mf/with-memo [libraries]
                         (d/removem (fn [[_ val]] (:is-indirect val)) libraries))

        ;; NOTE: we really don't need react on shared files
        shared-libraries
        (mf/deref refs/workspace-shared-files)

        on-tab-change
        (mf/use-fn #(reset! selected-tab* %))

        close-dialog-outside
        (mf/use-fn (fn [event]
                     (when (= (dom/get-target event) (dom/get-current-target event))
                       (modal/hide!))))

        close-dialog
        (mf/use-fn (fn [_]
                     (modal/hide!)
                     (modal/disallow-click-outside!)))]

    (mf/with-effect [team-id]
      (when team-id
        (st/emit! (dwl/fetch-shared-files {:team-id team-id}))))

    [:div {:class (stl/css :modal-overlay) :on-click close-dialog-outside}
     [:div {:class (stl/css :modal-dialog)}
      [:button {:class (stl/css :close-btn)
                :on-click close-dialog}
       close-icon]
      [:div {:class (stl/css :modal-title)}
       (tr "workspace.libraries.libraries")]
      [:& tab-container
       {:on-change-tab on-tab-change
        :selected selected-tab
        :collapsable false}
       [:& tab-element {:id :libraries :title (tr "workspace.libraries.libraries")}
        [:& libraries-tab {:file-id file-id
                           :shared? shared?
                           :linked-libraries libraries
                           :shared-libraries shared-libraries}]]
       [:& tab-element {:id :updates :title (tr "workspace.libraries.updates")}
        [:& updates-tab {:file-id file-id
                         :file-data file-data
                         :libraries libraries}]]]]]))

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
