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
   [app.main.data.workspace.libraries :as dwl]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.render :refer [component-svg]]
   [app.main.store :as st]
   [app.main.ui.components.color-bullet :as bc]
   [app.main.ui.components.link-button :as lb]
   [app.main.ui.components.search-bar :refer [search-bar]]
   [app.main.ui.components.tab-container :refer [tab-container tab-element]]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.context :as ctx]
   [app.main.ui.icons :as i]
   [app.util.color :as uc]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.strings :refer [matches-search]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

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

  (let [last-one (cond
                   (> colors-count 0) :color
                   (> graphics-count 0) :graphics
                   (> components-count 0) :components)]
    [:*
     (when (pos? components-count)
       [:*
        [:span {:class (stl/css :element-count)}
         (tr "workspace.libraries.components" components-count)]
        (when (not= last-one :components)
          [:span " · "])])

     (when (pos? graphics-count)
       [:*
        [:span {:class (stl/css :element-count)}
         (tr "workspace.libraries.graphics" graphics-count)]
        (when (not= last-one :graphics)
          [:span "  · "])])

     (when (pos? colors-count)
       [:*
        [:span {:class (stl/css :element-count)}
         (tr "workspace.libraries.colors" colors-count)]
        (when (not= last-one :colors)
          [:span "  · "])])

     (when (pos? typography-count)
       [:span {:class (stl/css :element-count)}
        (tr "workspace.libraries.typography" typography-count)])]))

(defn- describe-linked-library
  [library]
  (let [components-count (count (or (ctkl/components-seq (:data library)) []))
        graphics-count   (count (dm/get-in library [:data :media] []))
        colors-count     (count (dm/get-in library [:data :colors] []))
        typography-count (count (dm/get-in library [:data :typographies] []))]
    (describe-library components-count graphics-count colors-count typography-count)))

(defn- describe-external-library
  [library]
  (let [components-count (dm/get-in library [:library-summary :components :count] 0)
        graphics-count   (dm/get-in library [:library-summary :media :count] 0)
        colors-count     (dm/get-in library [:library-summary :colors :count] 0)
        typography-count (dm/get-in library [:library-summary :typographies :count] 0)]
    (describe-library components-count graphics-count colors-count typography-count)))

(mf/defc libraries-tab
  {::mf/wrap-props false}
  [{:keys [file-id shared? linked-libraries shared-libraries]}]
  (let [search-term*   (mf/use-state "")
        search-term    (deref search-term*)
        new-css-system (mf/use-ctx ctx/new-css-system)
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
          (->> shared-libraries
               (remove #(= (:id %) file-id))
               (remove #(contains? linked-libraries (:id %)))
               (filter #(matches-search (:name %) search-term))
               (sort-by (comp str/lower :name))))

        linked-libraries
        (mf/with-memo [linked-libraries]
          (->> (vals linked-libraries)
               (sort-by (comp str/lower :name))))

        change-search-term
        (mf/use-fn
         (mf/deps new-css-system)
         (fn [event]
           (let [value (if new-css-system
                         event
                         (-> (dom/get-target event)
                             (dom/get-value)))]
             (reset! search-term* value))))

        clear-search-term
        (mf/use-fn #(reset! search-term* ""))

        link-library
        (mf/use-fn
         (mf/deps file-id new-css-system)
         (fn [event]
           (let [library-id (if new-css-system
                              (some-> (dom/get-current-target event)
                                      (dom/get-data "library-id")
                                      (parse-uuid))
                              (some-> (dom/get-target event)
                                      (dom/get-data "library-id")
                                      (parse-uuid)))]
             (st/emit! (dwl/link-file-to-library file-id library-id)))))

        unlink-library
        (mf/use-fn
         (mf/deps file-id)
         (fn [event]
           (let [library-id (if new-css-system
                              (some-> (dom/get-current-target event)
                                      (dom/get-data "library-id")
                                      (parse-uuid))
                              (some-> (dom/get-target event)
                                      (dom/get-data "library-id")
                                      (parse-uuid)))]
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
                       :count-libraries 1}))))

        handle-key-down
        (mf/use-fn
         (fn [event]
           (let [enter?     (kbd/enter? event)
                 esc?       (kbd/esc? event)
                 input-node (dom/get-target event)]
             (when ^boolean enter?
               (dom/blur! input-node))
             (when ^boolean esc?
               (dom/blur! input-node)))))]

    (if new-css-system
      [:*
       [:div {:class (stl/css :section)}
        [:& title-bar {:collapsable? false
                       :title        (tr "workspace.libraries.in-this-file")
                       :class        (stl/css :title-spacing-lib)}]
        [:div {:class (stl/css :section-list)}

         [:div {:class (stl/css :section-list-item)}
          [:div
           [:div {:class (stl/css :item-name)} (tr "workspace.libraries.file-library")]
           [:div {:class (stl/css :item-contents)}
            [:& describe-library-blocks {:components-count (count components)
                                         :graphics-count (count media)
                                         :colors-count (count colors)
                                         :typography-count (count typographies)}]]]
          [:div
           (if ^boolean shared?
             [:input {:class (stl/css :item-unpublish)
                      :type "button"
                      :value (tr "common.unpublish")
                      :on-click unpublish}]
             [:input {:class (stl/css :item-publish)
                      :type "button"
                      :value (tr "common.publish")
                      :on-click publish}])]]

         (for [{:keys [id name] :as library} linked-libraries]
           [:div {:class (stl/css :section-list-item)
                  :key (dm/str id)}
            [:div
             [:div {:class (stl/css :item-name)} name]
             [:div {:class (stl/css :item-contents)}
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
                      :data-library-id (dm/str id)
                      :on-click unlink-library}
             i/delete-refactor]])]]

       [:div {:class (stl/css :section)}
        [:& title-bar {:collapsable? false
                       :title        (tr "workspace.libraries.shared-libraries")
                       :class        (stl/css :title-spacing-lib)}]
        [:div {:class (stl/css :libraries-search)}
         [:& search-bar {:on-change change-search-term
                         :value search-term
                         :placeholder (tr "workspace.libraries.search-shared-libraries")
                         :icon (mf/html [:span {:class (stl/css :search-icon)} i/search-refactor])}]]

        (if (seq shared-libraries)
          [:div {:class (stl/css :section-list-shared)}
           (for [{:keys [id name] :as library} shared-libraries]
             [:div {:class (stl/css :section-list-item)
                    :key (dm/str id)}
              [:div
               [:div {:class (stl/css :item-name)} name]
               [:div {:class (stl/css :item-contents)}
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
                        :on-click link-library}
               i/add-refactor]])]

          [:div {:class (stl/css :section-list-empty)}
           (if (nil? shared-libraries)
             i/loader-pencil
             (if (str/empty? search-term)
               (tr "workspace.libraries.no-shared-libraries-available")
               (tr "workspace.libraries.no-matches-for" search-term)))])]]

      [:*
       [:div.section
        [:div.section-title (tr "workspace.libraries.in-this-file")]
        [:div.section-list

         [:div.section-list-item
          [:div
           [:div.item-name (tr "workspace.libraries.file-library")]
           [:div.item-contents (describe-library
                                (count components)
                                (count media)
                                (count colors)
                                (count typographies))]]
          [:div
           (if ^boolean shared?
             [:input.item-button {:type "button"
                                  :value (tr "common.unpublish")
                                  :on-click unpublish}]
             [:input.item-button {:type "button"
                                  :value (tr "common.publish")
                                  :on-click publish}])]]

         (for [{:keys [id name] :as library} linked-libraries]
           [:div.section-list-item {:key (dm/str id)}
            [:div.item-name name]
            [:div.item-contents (describe-linked-library library)]
            [:input.item-button {:type "button"
                                 :value (tr "labels.remove")
                                 :data-library-id (dm/str id)
                                 :on-click unlink-library}]])]]

       [:div.section
        [:div.section-title (tr "workspace.libraries.shared-libraries")]
        [:div.libraries-search
         [:input.search-input
          {:placeholder (tr "workspace.libraries.search-shared-libraries")
           :type "text"
           :value search-term
           :on-change change-search-term
           :on-key-down handle-key-down}]
         (if (str/empty? search-term)
           [:div.search-icon
            i/search]
           [:div.search-icon.search-close
            {:on-click clear-search-term}
            i/close])]

        (if (seq shared-libraries)
          [:div.section-list
           (for [{:keys [id name] :as library} shared-libraries]
             [:div.section-list-item {:key (dm/str id)}
              [:div.item-name name]
              [:div.item-contents (describe-external-library library)]
              [:input.item-button {:type "button"
                                   :value (tr "workspace.libraries.add")
                                   :data-library-id (dm/str id)
                                   :on-click link-library}]])]

          [:div.section-list-empty
           (if (nil? shared-libraries)
             i/loader-pencil
             [:* i/library
              (if (str/empty? search-term)
                (tr "workspace.libraries.no-shared-libraries-available")
                (tr "workspace.libraries.no-matches-for" search-term))])])]])))

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

        new-css-system (mf/use-ctx ctx/new-css-system)

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

    (if new-css-system
      [:div {:class (stl/css :section)}
       (if (empty? libs-assets)
         [:div {:class (stl/css :section-list-empty)}
          (tr "workspace.libraries.no-libraries-need-sync")]
         [:*
          [:div {:class (stl/css :section-title)} (tr "workspace.libraries.library-updates")]

          [:div {:class (stl/css :section-list)}
           (for [[{:keys [id name] :as library}
                  exceeded
                  {:keys [components colors typographies]}] libs-assets]
             [:div {:class (stl/css :section-list-item)
                    :key (dm/str id)}
              [:div
               [:div {:class (stl/css :item-name)} name]
               [:div {:class (stl/css :item-contents)} (describe-library
                                                        (count components)
                                                        0
                                                        (count colors)
                                                        (count typographies))]]
              [:input {:type "button"
                       :class (stl/css-case :item-update true
                                            :disabled updating?)
                       :value (tr "workspace.libraries.update")
                       :data-library-id (dm/str id)
                       :on-click update}]

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
                                           :objects (:objects component)}]
                        [:div {:class (stl/css :name-block)}
                         [:span {:class (stl/css :item-name)
                                 :title (:name component)}
                          (:name component)]]])])
                  (when (:components exceeded)
                    [:div {:class (stl/css :libraries-updates-item)
                           :key (uuid/next)}
                     [:div {:class (stl/css :name-block.ellipsis)}
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
                        [:& bc/color-bullet {:color {:color (:color color)
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
                [:div {:class (stl/css :libraries-updates-see-all)}
                 [:& lb/link-button {:on-click see-all-assets
                                     :value (str "(" (tr "workspace.libraries.update.see-all-changes") ")")}]])])]])]

      [:div.section
       (if (empty? libs-assets)
         [:div.section-list-empty
          i/library
          (tr "workspace.libraries.no-libraries-need-sync")]
         [:*
          [:div.section-title (tr "workspace.libraries.library-updates")]

          [:div.section-list
           (for [[{:keys [id name] :as library}
                  exceeded
                  {:keys [components colors typographies]}] libs-assets]
             [:div.section-list-item {:key (dm/str id)}
              [:div.item-name name]
              [:div.item-contents (describe-library
                                   (count components)
                                   0
                                   (count colors)
                                   (count typographies))]
              [:input.item-button.item-update {:type "button"
                                               :class (stl/css-case new-css-system
                                                                    :disabled updating?)
                                               :value (tr "workspace.libraries.update")
                                               :data-library-id (dm/str id)
                                               :on-click update}]

              [:div.libraries-updates
               (when-not (empty? components)
                 [:div.libraries-updates-column
                  (for [component components]
                    [:div.libraries-updates-item {:key (dm/str (:id component))}
                     (let [component (ctf/load-component-objects (:data library) component)
                           root-shape (ctf/get-component-root (:data library) component)]
                       [:*
                        [:& component-svg {:root-shape root-shape
                                           :objects (:objects component)}]
                        [:div.name-block
                         [:span.item-name {:title (:name component)}
                          (:name component)]]])])
                  (when (:components exceeded)
                    [:div.libraries-updates-item {:key (uuid/next)}
                     [:div.name-block.ellipsis
                      [:span.item-name "(...)"]]])])

               (when-not (empty? colors)
                 [:div.libraries-updates-column {:style #js {"--bullet-size" "24px"}}
                  (for [color colors]
                    (let [default-name (cond
                                         (:gradient color) (uc/gradient-type->string (get-in color [:gradient :type]))
                                         (:color color) (:color color)
                                         :else (:value color))]
                      [:div.libraries-updates-item {:key (dm/str (:id color))}
                       [:*
                        [:& bc/color-bullet {:color {:color (:color color)
                                                     :opacity (:opacity color)}}]
                        [:div.name-block
                         [:span.item-name {:title (:name color)}
                          (:name color)]
                         (when-not (= (:name color) default-name)
                           [:span.color-value (:color color)])]]]))
                  (when (:colors exceeded)
                    [:div.libraries-updates-item {:key (uuid/next)}
                     [:div.name-block.ellipsis
                      [:span.item-name "(...)"]]])])

               (when-not (empty? typographies)
                 [:div.libraries-updates-column
                  (for [typography typographies]
                    [:div.libraries-updates-item {:key (dm/str (:id typography))}
                     [:*
                      [:div.typography-sample
                       {:style {:font-family (:font-family typography)
                                :font-weight (:font-weight typography)
                                :font-style (:font-style typography)}}
                       (tr "workspace.assets.typography.sample")]
                      [:div.name-block
                       [:span.item-name {:title (:name typography)}
                        (:name typography)]]]])
                  (when (:typographies exceeded)
                    [:div.libraries-updates-item {:key (uuid/next)}
                     [:div.name-block.ellipsis
                      [:span.item-name "(...)"]]])])]

              (when (or (pos? (:components exceeded))
                        (pos? (:colors exceeded))
                        (pos? (:typographies exceeded)))
                [:div.libraries-updates-see-all
                 [:& lb/link-button {:on-click see-all-assets
                                     :value (str "(" (tr "workspace.libraries.update.see-all-changes") ")")}]])])]])])))

(mf/defc libraries-dialog
  {::mf/register modal/components
   ::mf/register-as :libraries-dialog}
  [{:keys [starting-tab] :as props :or {starting-tab :libraries}}]
  (let [new-css-system (features/use-feature "styles/v2")
        project        (mf/deref refs/workspace-project)
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

        select-libraries-tab
        (mf/use-fn #(reset! selected-tab* :libraries))

        select-updates-tab
        (mf/use-fn #(reset! selected-tab* :updates))

        on-tab-change
        (mf/use-fn #(reset! selected-tab* %))

        close-dialog
        (mf/use-fn (fn [_]
                     (modal/hide!)
                     (modal/disallow-click-outside!)))]

    (mf/with-effect [team-id]
      (when team-id
        (st/emit! (dwl/fetch-shared-files {:team-id team-id}))))
    [:& (mf/provider ctx/new-css-system) {:value new-css-system}
     (if new-css-system
       [:div {:class (stl/css :modal-overlay)}
        [:div {:class (stl/css :modal-dialog)}
         [:button {:class (stl/css :close)
                   :on-click close-dialog}
          i/close-refactor]
         [:div {:class (stl/css :modal-title)}
          "Libraries"]
         [:div  {:class (stl/css :modal-content)}
          [:div {:class (stl/css :libraries-header)}
           [:& tab-container
            {:on-change-tab on-tab-change
             :selected selected-tab
             :collapsable? false}
            [:& tab-element {:id :libraries :title (tr "workspace.libraries.libraries")}
             [:div {:class (stl/css :libraries-content)}
              [:& libraries-tab {:file-id file-id
                                 :shared? shared?
                                 :linked-libraries libraries
                                 :shared-libraries shared-libraries}]]]
            [:& tab-element {:id :updates :title (tr "workspace.libraries.updates")}
             [:div {:class (stl/css :updates-content)}
              [:& updates-tab {:file-id file-id
                               :file-data file-data
                               :libraries libraries}]]]]]]]]

       [:div.modal-overlay
        [:div.modal.libraries-dialog
         [:a.close {:on-click close-dialog} i/close]
         [:div.modal-content
          [:div.libraries-header
           [:div.header-item
            {:class (stl/css-case new-css-system :active (= selected-tab :libraries))
             :on-click select-libraries-tab}
            (tr "workspace.libraries.libraries")]
           [:div.header-item
            {:class (stl/css-case new-css-system :active (= selected-tab :updates))
             :on-click select-updates-tab}
            (tr "workspace.libraries.updates")]]
          [:div.libraries-content
           (case selected-tab
             :libraries
             [:& libraries-tab {:file-id file-id
                                :shared? shared?
                                :linked-libraries libraries
                                :shared-libraries shared-libraries}]
             :updates
             [:& updates-tab {:file-id file-id
                              :file-data file-data
                              :libraries libraries}])]]]])]))

