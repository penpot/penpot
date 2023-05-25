;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.libraries
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.components-list :as ctkl]
   [app.main.data.modal :as modal]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
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
  (str
   (str/join " · "
             (cond-> []
               (pos? components-count)
               (conj (tr "workspace.libraries.components" components-count))

               (pos? graphics-count)
               (conj (tr "workspace.libraries.graphics" graphics-count))

               (pos? colors-count)
               (conj (tr "workspace.libraries.colors" colors-count))

               (pos? typography-count)
               (conj (tr "workspace.libraries.typography" typography-count))))
   "\u00A0"))

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
  (let [search-term*  (mf/use-state "")
        search-term   (deref search-term*)

        library-ref   (mf/with-memo [file-id]
                        (create-file-library-ref file-id))
        library       (deref library-ref)
        colors        (:colors library)
        components    (:components library)
        media         (:media library)
        typographies  (:typographies library)

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
         (fn [event]
           (let [value (-> (dom/get-target event)
                           (dom/get-value))]
             (reset! search-term* value))))

        clear-search-term
        (mf/use-fn #(reset! search-term* ""))

        link-library
        (mf/use-fn
         (mf/deps file-id)
         (fn [event]
           (let [library-id (some-> (dom/get-target event)
                                    (dom/get-data "library-id")
                                    (parse-uuid))]
             (st/emit! (dwl/link-file-to-library file-id library-id)))))

        unlink-library
        (mf/use-fn
         (mf/deps file-id)
         (fn [event]
           (let [library-id (some-> (dom/get-target event)
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
         #(st/emit! (dwl/set-file-shared file-id true)))

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
                 input-node (dom/event->target event)]
             (when ^boolean enter?
               (dom/blur! input-node))
             (when ^boolean esc?
               (dom/blur! input-node)))))]

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
              (tr "workspace.libraries.no-matches-for" search-term))])])]]))


(mf/defc updates-tab
  {::mf/wrap-props false}
  [{:keys [file-id libraries]}]
  (let [libraries (mf/with-memo [libraries]
                    (filter #(> (:modified-at %) (:synced-at %)) (vals libraries)))

        update    (mf/use-fn
                   (mf/deps file-id)
                   (fn [event]
                     (let [library-id (some-> (dom/get-target event)
                                              (dom/get-data "library-id")
                                              (parse-uuid))]
                       (st/emit! (dwl/sync-file file-id library-id)))))]
    [:div.section
     (if (empty? libraries)
       [:div.section-list-empty
        i/library
        (tr "workspace.libraries.no-libraries-need-sync")]
       [:*
        [:div.section-title (tr "workspace.libraries.library")]
        [:div.section-list
         (for [{:keys [id name] :as library} libraries]
           [:div.section-list-item {:key (dm/str id)}
            [:div.item-name name]
            [:div.item-contents (describe-external-library library)]
            [:input.item-button {:type "button"
                                 :value (tr "workspace.libraries.update")
                                 :data-library-id (dm/str id)
                                 :on-click update}]])]])]))

(mf/defc libraries-dialog
  {::mf/register modal/components
   ::mf/register-as :libraries-dialog}
  []
  (let [project       (mf/deref refs/workspace-project)
        file          (mf/deref ref:workspace-file)

        team-id       (:team-id project)
        file-id       (:id file)
        shared?       (:is-shared file)

        selected-tab* (mf/use-state :libraries)
        selected-tab  (deref selected-tab*)

        libraries     (mf/deref refs/workspace-libraries)
        libraries     (mf/with-memo [libraries]
                        (d/removem (fn [[_ val]] (:is-indirect val)) libraries))

        ;; NOTE: we really don't need react on shared files
        shared-libraries
        (mf/deref refs/workspace-shared-files)

        select-libraries-tab
        (mf/use-fn #(reset! selected-tab* :libraries))

        select-updates-tab
        (mf/use-fn #(reset! selected-tab* :updates))

        close-dialog
        (mf/use-fn #(modal/hide!))]

    (mf/with-effect [team-id]
      (when team-id
        (st/emit! (dwl/fetch-shared-files {:team-id team-id}))))

    [:div.modal-overlay
     [:div.modal.libraries-dialog
      [:a.close {:on-click close-dialog} i/close]
      [:div.modal-content
       [:div.libraries-header
        [:div.header-item
         {:class (dom/classnames :active (= selected-tab :libraries))
          :on-click select-libraries-tab}
         (tr "workspace.libraries.libraries")]
        [:div.header-item
         {:class (dom/classnames :active (= selected-tab :updates))
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
                           :libraries libraries}])]]]]))

