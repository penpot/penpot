;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.libraries
  (:require
   [app.common.data :as d]
   [app.common.types.components-list :as ctkl]
   [app.main.data.dashboard :as dd]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i] [app.main.ui.workspace.sidebar.assets :as a]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.strings :refer [matches-search]]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

(def workspace-file
  (l/derived :workspace-file st/state))

(defn library-str
  [components-count graphics-count colors-count typography-count]
  (str
   (str/join " · "
             (cond-> []
               (< 0 components-count)
               (conj (tr "workspace.libraries.components" components-count))

               (< 0 graphics-count)
               (conj (tr "workspace.libraries.graphics" graphics-count))

               (< 0 colors-count)
               (conj (tr "workspace.libraries.colors" colors-count))

               (< 0 typography-count)
               (conj (tr "workspace.libraries.typography" typography-count))))
   "\u00A0"))

(defn local-library-str
  [library]
  (let [components-count (count (or (ctkl/components-seq (:data library)) []))
        graphics-count (count (get-in library [:data :media] []))
        colors-count (count (get-in library [:data :colors] []))
        typography-count (count (get-in library [:data :typographies] []))]
    (library-str components-count graphics-count colors-count typography-count)))

(defn external-library-str
  [library]
  (let [components-count (get-in library [:library-summary :components :count] 0)
        graphics-count (get-in library [:library-summary :media :count] 0)
        colors-count (get-in library [:library-summary :colors :count] 0)
        typography-count (get-in library [:library-summary :typographies :count] 0)]
    (library-str components-count graphics-count colors-count typography-count)))

(mf/defc libraries-tab
  [{:keys [file colors typographies media components libraries shared-files] :as props}]
  (let [search-term (mf/use-state "")

        sorted-libraries (->> (vals libraries)
                              (sort-by #(str/lower (:name %))))

        filtered-files   (->> shared-files
                              (filter #(not= (:id %) (:id file)))
                              (filter #(nil? (get libraries (:id %))))
                              (filter #(matches-search (:name %) @search-term))
                              (sort-by #(str/lower (:name %))))

        on-search-term-change
        (mf/use-callback
         (fn [event]
           (let [value (-> (dom/get-target event)
                           (dom/get-value))]
             (reset! search-term value))))

        on-search-clear
        (mf/use-callback
         (fn [_]
           (reset! search-term "")))

        link-library
        (mf/use-callback (mf/deps file) #(st/emit! (dwl/link-file-to-library (:id file) %)))

        unlink-library
        (mf/use-callback
         (mf/deps file)
         (fn [library-id]
           (st/emit! (dwl/unlink-file-from-library (:id file) library-id)
                     (dwl/sync-file (:id file) library-id))))
        add-shared
        (mf/use-callback
         (mf/deps file)
         #(st/emit! (dwl/set-file-shared (:id file) true)))

        del-shared
        (mf/use-callback
         (mf/deps file)
         (fn [_]
           (st/emit! (dd/fetch-libraries-using-files [file]))
           (st/emit! (modal/show
                      {:type :delete-shared
                       :origin :unpublish
                       :on-accept (fn []
                                    (st/emit! (dwl/set-file-shared (:id file) false))
                                    (modal/show! :libraries-dialog {}))
                       :on-cancel #(modal/show! :libraries-dialog {})
                       :count-libraries 1}))))
        handle-key-down
        (mf/use-callback
         (fn [event]
           (let [enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)
                 input-node (dom/event->target event)]

             (when enter?
               (dom/blur! input-node))
             (when esc?
               (dom/blur! input-node)))))]
    [:*
     [:div.section
      [:div.section-title (tr "workspace.libraries.in-this-file")]
      [:div.section-list
       [:div.section-list-item
        [:div
         [:div.item-name (tr "workspace.libraries.file-library")]
         [:div.item-contents (library-str (count components) (count media) (count colors) (count typographies) )]]
        [:div
         (if (:is-shared file)
           [:input.item-button {:type "button"
                                :value (tr "common.unpublish")
                                :on-click del-shared}]
           [:input.item-button {:type "button"
                                :value (tr "common.publish")
                                :on-click add-shared}])]]

       (for [library sorted-libraries]
         [:div.section-list-item {:key (:id library)}
          [:div.item-name (:name library)]
          [:div.item-contents (local-library-str library)]
          [:input.item-button {:type "button"
                               :value (tr "labels.remove")
                               :on-click #(unlink-library (:id library))}]])
       ]]
     [:div.section
      [:div.section-title (tr "workspace.libraries.shared-libraries")]
      [:div.libraries-search
       [:input.search-input
        {:placeholder (tr "workspace.libraries.search-shared-libraries")
         :type "text"
         :value @search-term
         :on-change on-search-term-change
         :on-key-down handle-key-down}]
       (if (str/empty? @search-term)
         [:div.search-icon
          i/search]
         [:div.search-icon.search-close
          {:on-click on-search-clear}
          i/close])]
      (if (> (count filtered-files) 0)
        [:div.section-list
         (for [file filtered-files]
           [:div.section-list-item {:key (:id file)}
            [:div.item-name (:name file)]
            [:div.item-contents (external-library-str file)]
            [:input.item-button {:type "button"
                                 :value (tr "workspace.libraries.add")
                                 :on-click #(link-library (:id file))}]])]

        [:div.section-list-empty
         (if (nil? shared-files)
           i/loader-pencil
           [:* i/library
            (if (str/empty? @search-term)
              (tr "workspace.libraries.no-shared-libraries-available")
              (tr "workspace.libraries.no-matches-for" @search-term))])])]]))


(mf/defc updates-tab
  [{:keys [file libraries] :as props}]
  (let [libraries-need-sync (filter #(> (:modified-at %) (:synced-at %))
                                        (vals libraries))
        update-library #(st/emit! (dwl/sync-file (:id file) %))]
  [:div.section
   (if (empty? libraries-need-sync)
     [:div.section-list-empty
      i/library
      (tr "workspace.libraries.no-libraries-need-sync")]
     [:*
       [:div.section-title (tr "workspace.libraries.library")]
       [:div.section-list
        (for [library libraries-need-sync]
          [:div.section-list-item {:key (:id library)}
           [:div.item-name (:name library)]
           [:div.item-contents (external-library-str library)]
           [:input.item-button {:type "button"
                                :value (tr "workspace.libraries.update")
                                :on-click #(update-library (:id library))}]])]])]))

(mf/defc libraries-dialog
  {::mf/register modal/components
   ::mf/register-as :libraries-dialog}
  [{:keys [] :as ctx}]
  (let [selected-tab (mf/use-state :libraries)
        project      (mf/deref refs/workspace-project)
        file         (mf/deref workspace-file)

        libraries    (->> (mf/deref refs/workspace-libraries)
                          (d/removem (fn [[_ val]] (:is-indirect val))))
        shared-files (mf/deref refs/workspace-shared-files)

        colors-ref           (mf/use-memo (mf/deps (:id file)) #(a/file-colors-ref (:id file)))
        colors               (mf/deref colors-ref)

        typography-ref       (mf/use-memo (mf/deps (:id file)) #(a/file-typography-ref (:id file)))
        typographies         (mf/deref typography-ref)

        media-ref            (mf/use-memo (mf/deps (:id file)) #(a/file-media-ref (:id file)))
        media                (mf/deref media-ref)

        components-ref       (mf/use-memo (mf/deps (:id file)) #(a/file-components-ref (:id file)))
        components           (mf/deref components-ref)

        change-tab   #(reset! selected-tab %)
        close        #(modal/hide!)]

    (mf/use-effect
     (mf/deps project)
     (fn []
       (when (:team-id project)
         (st/emit! (dwl/fetch-shared-files {:team-id (:team-id project)})))))

    [:div.modal-overlay
     [:div.modal.libraries-dialog
      [:a.close {:on-click close} i/close]
      [:div.modal-content
       [:div.libraries-header
        [:div.header-item
         {:class (dom/classnames :active (= @selected-tab :libraries))
          :on-click #(change-tab :libraries)}
         (tr "workspace.libraries.libraries")]
        [:div.header-item
         {:class (dom/classnames :active (= @selected-tab :updates))
          :on-click #(change-tab :updates)}
         (tr "workspace.libraries.updates")]]
       [:div.libraries-content
        (case @selected-tab
          :libraries
          [:& libraries-tab {:file file
                             :colors colors
                             :typographies typographies
                             :media media
                             :components components
                             :libraries libraries
                             :shared-files shared-files}]
          :updates
          [:& updates-tab {:file file
                           :libraries libraries}])]]]]))

