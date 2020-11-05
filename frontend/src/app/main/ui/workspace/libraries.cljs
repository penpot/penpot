;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>
;; Copyright (c) 2016 Juan de la Cruz <delacruzgarciajuan@gmail.com>

(ns app.main.ui.workspace.libraries
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [t tr]]
   [app.util.data :refer [classnames matches-search]]
   [app.main.store :as st]
   [app.main.refs :as refs]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.ui.icons :as i]
   [app.main.data.modal :as modal]))

(def workspace-file
  (l/derived :workspace-file st/state))

(defn contents-str
  [library]
  (let [components-count (count (get-in library [:data :components] []))
        graphics-count (count (get-in library [:data :media] []))
        colors-count (count (get-in library [:data :colors] []))
        typography-count (count (get-in library [:data :typographies] []))]
    ;; Include a &nbsp; so this block has always some content
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
      "\u00A0")))

(mf/defc libraries-tab
  [{:keys [file libraries shared-files] :as props}]
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
         (fn [event]
           (reset! search-term "")))

        link-library
        (mf/use-callback (mf/deps file) #(st/emit! (dw/link-file-to-library (:id file) %)))

        unlink-library
        (mf/use-callback (mf/deps file) #(st/emit! (dw/unlink-file-from-library (:id file) %)))]
    [:*
     [:div.section
      [:div.section-title (tr "workspace.libraries.in-this-file")]
      [:div.section-list
       [:div.section-list-item
        [:div.item-name (tr "workspace.libraries.file-library")]
        [:div.item-contents (contents-str file)]]
       (for [library sorted-libraries]
         [:div.section-list-item {:key (:id library)}
          [:div.item-name (:name library)]
          [:div.item-contents (contents-str library)]
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
         :on-change on-search-term-change}]
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
            [:div.item-contents (contents-str file)]
            [:input.item-button {:type "button"
                                 :value (tr "workspace.libraries.add")
                                 :on-click #(link-library (:id file))}]])]
        [:div.section-list-empty
         i/library
         (if (str/empty? @search-term)
           (tr "workspace.libraries.no-shared-libraries-available")
           (tr "workspace.libraries.no-matches-for" @search-term))])]]))


(mf/defc updates-tab
  [{:keys [file libraries] :as props}]
  (let [libraries-need-sync (filter #(> (:modified-at %) (:synced-at %))
                                        (vals libraries))
        update-library #(st/emit! (dwl/sync-file %))]
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
           [:div.item-contents (contents-str library)]
           [:input.item-button {:type "button"
                                :value (tr "workspace.libraries.update")
                                :on-click #(update-library (:id library))}]])]])]))


(mf/defc libraries-dialog
  {::mf/register modal/components
   ::mf/register-as :libraries-dialog}
  [{:keys [] :as ctx}]
  (let [selected-tab (mf/use-state :libraries)

        locale       (mf/deref i18n/locale)
        project      (mf/deref refs/workspace-project)
        file         (mf/deref workspace-file)
        libraries    (mf/deref refs/workspace-libraries)
        shared-files (mf/deref refs/workspace-shared-files)

        change-tab   #(reset! selected-tab %)
        close        #(modal/hide!)]

    (mf/use-effect
     (mf/deps project)
     (fn []
       (when (:team-id project)
         (st/emit! (dw/fetch-shared-files {:team-id (:team-id project)})))))

    [:div.modal-overlay
     [:div.modal.libraries-dialog
      [:a.close {:on-click close} i/close]
      [:div.modal-content
       [:div.libraries-header
        [:div.header-item
         {:class (classnames :active (= @selected-tab :libraries))
          :on-click #(change-tab :libraries)}
         (t locale "workspace.libraries.libraries")]
        [:div.header-item
         {:class (classnames :active (= @selected-tab :updates))
          :on-click #(change-tab :updates)}
         (t locale "workspace.libraries.updates")]]
       [:div.libraries-content
        (case @selected-tab
          :libraries
          [:& libraries-tab {:file file
                             :libraries libraries
                             :shared-files shared-files}]
          :updates
          [:& updates-tab {:file file
                           :libraries libraries}])]]]]))

