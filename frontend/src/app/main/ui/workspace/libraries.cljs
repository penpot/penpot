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
   [app.util.dom :as dom]
   [app.util.i18n :refer (tr)]
   [app.util.data :refer [classnames matches-search]]
   [app.main.store :as st]
   [app.main.refs :as refs]
   [app.main.data.workspace :as dw]
   [app.main.ui.icons :as i]
   [app.main.ui.modal :as modal]))

(mf/defc libraries-tab
  [{:keys [file libraries shared-files] :as props}]
  (let [state (mf/use-state {:search-term ""})

        sorted-libraries (->> (vals libraries)
                              (sort-by #(str/lower (:name %))))

        filtered-files (->> shared-files
                            (filter #(not= (:id %) (:id file)))
                            (filter #(nil? (get libraries (:id %))))
                            (filter #(matches-search (:name %) (:search-term @state)))
                            (sort-by #(str/lower (:name %))))

        on-search-term-change (fn [event]
                               (let [value (-> (dom/get-target event)
                                               (dom/get-value))]
                                 (swap! state assoc :search-term value)))

        on-search-clear-click (fn [event]
                                (swap! state assoc :search-term ""))

        link-library (fn [library-id]
                       (st/emit! (dw/link-file-to-library (:id file) library-id)))

        unlink-library (fn [library-id]
                         (st/emit! (dw/unlink-file-from-library (:id file) library-id)))

        contents-str (fn [library graphics-count colors-count]
                       (str
                         (str/join " · "
                                   (cond-> []
                                     (< 0 graphics-count)
                                     (conj (tr "workspace.libraries.graphics" graphics-count))

                                     (< 0 colors-count)
                                     (conj (tr "workspace.libraries.colors" colors-count))))
                         "\u00A0"))] ;; Include a &nbsp; so this block has always some content
    [:*
      [:div.section
       [:div.section-title (tr "workspace.libraries.in-this-file")]
       [:div.section-list
        [:div.section-list-item
         [:div.item-name (tr "workspace.libraries.file-library")]
         [:div.item-contents (contents-str file
                                           (count (:media-objects file))
                                           (count (:colors file)))]]
        (for [library sorted-libraries]
          [:div.section-list-item {:key (:id library)}
           [:div.item-name (:name library)]
           [:div.item-contents (contents-str library
                                             (count (:media-objects library))
                                             (count (:colors library)))]
           [:input.item-button {:type "button"
                                :value (tr "workspace.libraries.remove")
                                :on-click #(unlink-library (:id library))}]])
        ]]
      [:div.section
       [:div.section-title (tr "workspace.libraries.shared-libraries")]
       [:div.libraries-search
        [:input.search-input
         {:placeholder (tr "workspace.libraries.search-shared-libraries")
          :type "text"
          :value (:search-term @state)
          :on-change on-search-term-change}]
        (if (str/empty? (:search-term @state))
          [:div.search-icon
           i/search]
          [:div.search-icon.search-close
           {:on-click on-search-clear-click}
           i/close])]
       (if (> (count filtered-files) 0)
         [:div.section-list
          (for [file filtered-files]
            [:div.section-list-item {:key (:id file)}
             [:div.item-name (:name file)]
             [:div.item-contents (contents-str file
                                               (:graphics-count file)
                                               (:colors-count file))]
             [:input.item-button {:type "button"
                                  :value (tr "workspace.libraries.add")
                                  :on-click #(link-library (:id file))}]])]
         [:div.section-list-empty
          i/library
          (if (str/empty? (:search-term @state))
            (tr "workspace.libraries.no-shared-libraries-available")
            (tr "workspace.libraries.no-matches-for" (:search-term @state)))])]]))


(mf/defc updates-tab
  []
  [:div])


(mf/defc libraries-dialog
  [{:keys [] :as ctx}]
  (let [state (mf/use-state {:current-tab :libraries})

        current-tab (:current-tab @state)

        file         (mf/deref refs/workspace-file)
        libraries    (mf/deref refs/workspace-libraries)
        shared-files (mf/deref refs/workspace-shared-files)

        change-tab (fn [tab]
                     (swap! state assoc :current-tab tab))

        close (fn [event]
                (dom/prevent-default event)
                (modal/hide!))]

    (mf/use-effect
      #(st/emit! (dw/fetch-shared-files)))

    [:div.modal-overlay
     [:div.modal.libraries-dialog
      [:a.close {:on-click close} i/close]
      [:div.modal-content
       [:div.libraries-header
        [:div.header-item
         {:class (classnames :active (= current-tab :libraries))
          :on-click #(change-tab :libraries)}
         (tr "workspace.libraries.libraries")]
        [:div.header-item
         {:class (classnames :active (= current-tab :updates))
          :on-click #(change-tab :updates)}
         (tr "workspace.libraries.updates")]]
       [:div.libraries-content
        (case current-tab
          :libraries
          [:& libraries-tab {:file file
                             :libraries libraries
                             :shared-files shared-files}]
          :updates
          [:& updates-tab {}])]]]]))

