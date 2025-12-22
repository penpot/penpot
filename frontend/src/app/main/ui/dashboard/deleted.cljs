;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.deleted
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.geom.point :as gpt]
   [app.main.data.common :as dcm]
   [app.main.data.dashboard :as dd]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.context-menu-a11y :refer [context-menu*]]
   [app.main.ui.dashboard.grid :refer [grid*]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.product.empty-placeholder :refer [empty-placeholder*]]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.icons :as deprecated-icon]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [okulary.core :as l]
   [rumext.v2 :as mf]))


(def ^:private menu-icon
  (deprecated-icon/icon-xref :menu (stl/css :menu-icon)))

(mf/defc header*
  {::mf/props :obj
   ::mf/private true}
  []
  [:header {:class (stl/css :dashboard-header) :data-testid "dashboard-header"}
   [:div#dashboard-deleted-title {:class (stl/css :dashboard-title)}
    [:h1 (tr "dashboard.projects-title")]]])

(mf/defc deleted-project-menu*
  [{:keys [project files team-id show on-close top left]}]
  (let [top  (d/nilv top 0)
        left (d/nilv left 0)

        file-ids
        (mf/with-memo [files]
          (into #{} d/xf:map-id files))

        restore-fn
        (fn [_]
          (st/emit! (dd/restore-files-immediately
                     (with-meta {:team-id team-id :ids file-ids}
                       {:on-success #(st/emit! (ntf/success (tr "restore-modal.success-restore-immediately" (:name project)))
                                               (dd/fetch-projects team-id)
                                               (dd/fetch-deleted-files team-id))
                        :on-error #(st/emit! (ntf/error (tr "restore-modal.error-restore-project" (:name project))))}))))

        on-restore-project
        (fn []
          (st/emit!
           (modal/show {:type :confirm
                        :title (tr "restore-modal.restore-project.title")
                        :message (tr "restore-modal.restore-project.description" (:name project))
                        :accept-style :primary
                        :accept-label (tr "labels.continue")
                        :on-accept restore-fn})))

        delete-fn
        (fn [_]
          (st/emit! (ntf/success (tr "delete-forever-modal.success-delete-immediately" (:name project)))
                    (dd/delete-files-immediately
                     {:team-id team-id
                      :ids file-ids})
                    (dd/fetch-projects team-id)
                    (dd/fetch-deleted-files team-id)))

        on-delete-project
        (fn []
          (st/emit!
           (modal/show {:type :confirm
                        :title (tr "delete-forever-modal.title")
                        :message (tr "delete-forever-modal.delete-project.description" (:name project))
                        :accept-label (tr "dashboard.deleted.delete-forever")
                        :on-accept delete-fn})))
        options
        [{:name   (tr "dashboard.deleted.restore-project")
          :id     "project-restore"
          :handler on-restore-project}
         {:name   (tr "dashboard.deleted.delete-project")
          :id     "project-delete"
          :handler on-delete-project}]]

    [:> context-menu*
     {:on-close on-close
      :show show
      :fixed (or (not= top 0) (not= left 0))
      :min-width true
      :top top
      :left left
      :options options}]))

(mf/defc deleted-project-item*
  {::mf/props :obj
   ::mf/private true}
  [{:keys [project team files]}]
  (let [project-files  (filterv #(= (:project-id %) (:id project)) files)

        empty?         (empty? project-files)
        selected-files (mf/deref refs/selected-files)

        dstate         (mf/deref refs/dashboard-local)
        edit-id        (:project-for-edit dstate)

        local          (mf/use-state
                        #(do {:menu-open false
                              :menu-pos nil
                              :edition (= (:id project) edit-id)}))

        [rowref limit] (hooks/use-dynamic-grid-item-width)

        on-menu-click
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)

           (let [client-position (dom/get-client-position event)
                 position (if (and (nil? (:y client-position)) (nil? (:x client-position)))
                            (let [target-element (dom/get-target event)
                                  points         (dom/get-bounding-rect target-element)
                                  y              (:top points)
                                  x              (:left points)]
                              (gpt/point x y))
                            client-position)]
             (swap! local assoc
                    :menu-open true
                    :menu-pos position))))

        on-menu-close
        (mf/use-fn #(swap! local assoc :menu-open false))

        handle-menu-click
        (mf/use-callback
         (mf/deps on-menu-click)
         (fn [event]
           (when (kbd/enter? event)
             (dom/stop-propagation event)
             (on-menu-click event))))]

    [:article {:class (stl/css-case :dashboard-project-row true)}
     [:header {:class (stl/css :project)}
      [:div {:class (stl/css :project-name-wrapper)}
       [:h2  {:class (stl/css :project-name)
              :title (:name project)}
        (:name project)]

       (when (:deleted-at project)
         [:div {:class (stl/css :info-wrapper)}
          [:div {:class (stl/css-case :project-actions true)}

           [:button {:class (stl/css :options-btn)
                     :on-click on-menu-click
                     :title (tr "dashboard.options")
                     :aria-label  (tr "dashboard.options")
                     :data-testid "project-options"
                     :on-key-down handle-menu-click}
            menu-icon]]

          (when (:menu-open @local)
            [:> deleted-project-menu*
             {:project project
              :files project-files
              :team-id (:id team)
              :show (:menu-open @local)
              :left (+ 24 (:x (:menu-pos @local)))
              :top (:y (:menu-pos @local))
              :on-close on-menu-close}])])]]

     [:div {:class (stl/css :grid-container) :ref rowref}
      (if ^boolean empty?
        [:> empty-placeholder* {:title (tr "dashboard.empty-placeholder-files-title")
                                :class (stl/css :placeholder-placement)
                                :type 1
                                :subtitle (tr "dashboard.empty-placeholder-files-subtitle")}]

        [:> grid*
         {:project project
          :files project-files
          :origin :deleted
          :can-edit false
          :can-restore true
          :limit limit
          :selected-files selected-files}])]]))

(def ^:private ref:deleted-files
  (l/derived :deleted-files st/state))

(mf/defc deleted-section*
  {::mf/props :obj}
  [{:keys [team projects]}]
  (let [deleted-map
        (mf/deref ref:deleted-files)

        projects
        (mf/with-memo [projects deleted-map]
          (->> projects
               (filter (fn [project]
                         (or (:deleted-at project)
                             (when deleted-map
                               (some #(= (:id project) (:project-id %))
                                     (vals deleted-map))))))
               (filter (fn [project]
                         (when deleted-map
                           (some #(= (:id project) (:project-id %))
                                 (vals deleted-map)))))
               (sort-by :modified-at)
               (reverse)))

        team-id
        (get team :id)

        ;; Calculate deletion days based on team subscription
        deletion-days
        (let [subscription (get team :subscription)
              sub-type     (get subscription :type)
              sub-status   (get subscription :status)
              canceled?    (contains? #{"canceled" "unpaid"} sub-status)]
          (cond
            (and (= "unlimited" sub-type) (not canceled?)) 30
            (and (= "enterprise" sub-type) (not canceled?)) 90
            :else 7))

        on-clear
        (mf/use-fn
         (mf/deps team-id deleted-map)
         (fn []
           (when deleted-map
             (let [file-ids (into #{} (keys deleted-map))]
               (when (seq file-ids)
                 (st/emit!
                  (modal/show {:type :confirm
                               :title (tr "delete-forever-modal.title")
                               :message (tr "delete-forever-modal.delete-all.description" (count file-ids))
                               :accept-label (tr "dashboard.deleted.delete-forever")
                               :on-accept #(st/emit!
                                            (dd/delete-files-immediately
                                             {:team-id team-id
                                              :ids file-ids})
                                            (dd/fetch-projects team-id)
                                            (dd/fetch-deleted-files team-id))})))))))

        restore-fn
        (fn [file-ids]
          (st/emit! (dd/restore-files-immediately
                     (with-meta {:team-id team-id :ids file-ids}
                       {:on-success #(st/emit! (dd/fetch-projects team-id)
                                               (dd/fetch-deleted-files team-id))
                        :on-error #(st/emit! (ntf/error (tr "restore-modal.error-restore-files")))}))))

        on-restore-all
        (mf/use-fn
         (mf/deps team-id deleted-map)
         (fn []
           (when deleted-map
             (let [file-ids (into #{} (keys deleted-map))]
               (when (seq file-ids)
                 (st/emit!
                  (modal/show {:type :confirm
                               :title (tr "restore-modal.restore-all.title")
                               :message (tr "restore-modal.restore-all.description" (count file-ids))
                               :accept-label (tr "labels.continue")
                               :accept-style :primary
                               :on-accept #(restore-fn file-ids)})))))))

        on-recent-click
        (mf/use-fn
         (mf/deps team-id)
         (fn []
           (st/emit! (dcm/go-to-dashboard-recent :team-id team-id))))]

    (mf/with-effect [team-id]
      (st/emit! (dd/fetch-projects team-id)
                (dd/fetch-deleted-files team-id)
                (dd/clear-selected-files)))

    [:*
     [:> header* {:team team}]
     [:section {:class (stl/css :dashboard-container :no-bg)}
      [:*
       [:div {:class (stl/css :no-bg)}

        [:div {:class (stl/css :nav-options)}
         [:> button* {:variant "ghost"
                      :data-testid "recent-tab"
                      :type "button"
                      :on-click on-recent-click}
          (tr "dashboard.labels.recent")]
         [:div {:class (stl/css :selected)
                :data-testid "deleted-tab"}
          (tr "dashboard.labels.deleted")]]

        [:div {:class (stl/css :deleted-content)}
         [:div {:class (stl/css :deleted-info)}
          [:div
           (tr "dashboard.deleted.info-text")
           [:span {:class (stl/css :info-text-highlight)}
            (tr "dashboard.deleted.info-days" deletion-days)]
           (tr "dashboard.deleted.info-text2")]
          [:div
           (tr "dashboard.deleted.restore-text")]]
         [:div {:class (stl/css :deleted-options)}
          [:> button* {:variant "ghost"
                       :type "button"
                       :on-click on-restore-all}
           (tr "dashboard.deleted.restore-all")]
          [:> button* {:variant "destructive"
                       :type "button"
                       :icon "delete"
                       :on-click on-clear}
           (tr "dashboard.deleted.clear")]]]

        (when (seq projects)
          (for [{:keys [id] :as project} projects]
            (let [files (when deleted-map
                          (->> (vals deleted-map)
                               (filterv #(= id (:project-id %)))
                               (sort-by :modified-at #(compare %2 %1))))]
              [:> deleted-project-item* {:project project
                                         :team team
                                         :files files
                                         :key id}])))]]]]))
