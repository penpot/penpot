;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.file-menu
  (:require
   [app.common.data :as d]
   [app.main.data.common :as dcm]
   [app.main.data.dashboard :as dd]
   [app.main.data.event :as-alias ev]
   [app.main.data.exports.files :as fexp]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.repo :as rp]
   [app.main.router :as rt]
   [app.main.store :as st]
   [app.main.ui.components.context-menu-a11y :refer [context-menu*]]
   [app.main.ui.context :as ctx]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(defn- get-project-name
  [project]
  (if (:is-default project)
    (tr "labels.drafts")
    (:name project)))

(defn- get-project-id
  [project]
  (str (:id project)))

(defn- get-team-name
  [team]
  (if (:is-default team)
    (tr "dashboard.your-penpot")
    (:name team)))

(defn- group-by-team
  "Group projects by team."
  [projects]
  (reduce (fn [teams project]
            (update teams
                    (:team-id project)
                    #(if (nil? %)
                       {:id (:team-id project)
                        :name (:team-name project)
                        :is-default (:is-default-team project)
                        :projects [project]}
                       (update % :projects conj project))))
          {}
          projects))

(mf/defc file-menu*
  [{:keys [files on-edit on-close top left navigate origin parent-id can-edit can-restore]}]

  (assert (seq files) "missing `files` prop")
  (assert (fn? on-edit) "missing `on-edit` prop")
  (assert (fn? on-close) "missing `on-close` prop")
  (assert (boolean? navigate) "missing `navigate` prop")

  (let [is-lib-page?     (= :libraries origin)
        is-search-page?  (= :search origin)
        top              (or top 0)
        left             (or left 0)

        file             (first files)
        file-count       (count files)
        multi?           (> file-count 1)

        current-team-id  (mf/use-ctx ctx/current-team-id)
        teams*           (mf/use-state nil)
        teams            (deref teams*)

        current-team     (get teams current-team-id)
        other-teams      (remove #(= (:id %) current-team-id) (vals teams))
        current-projects (remove #(= (:id %) (:project-id file))
                                 (:projects current-team))

        on-new-tab
        (fn [_]
          (st/emit! (dcm/go-to-workspace
                     {:file-id (:id file)
                      ::rt/new-window true})))

        on-duplicate
        (fn [_]
          (apply st/emit! (map dd/duplicate-file files))
          (st/emit! (ntf/success (tr "dashboard.success-duplicate-file" (i18n/c file-count)))))

        on-delete-accept
        (fn [_]
          (apply st/emit! (map dd/delete-file files))
          (st/emit! (ntf/success (tr "dashboard.success-delete-file" (i18n/c file-count)))
                    (dd/clear-selected-files)))

        on-delete
        (fn [event]
          (dom/stop-propagation event)
          (let [num-shared (filter #(:is-shared %) files)]

            (if (< 0 (count num-shared))
              (st/emit! (modal/show
                         {:type :delete-shared-libraries
                          :origin :delete
                          :ids (into #{} (map :id) files)
                          :on-accept on-delete-accept
                          :count-libraries (count num-shared)}))

              (if multi?
                (st/emit! (modal/show
                           {:type :confirm
                            :title (tr "modals.delete-file-multi-confirm.title" file-count)
                            :message (tr "modals.delete-file-multi-confirm.message" file-count)
                            :accept-label (tr "modals.delete-file-multi-confirm.accept" file-count)
                            :on-accept on-delete-accept}))
                (st/emit! (modal/show
                           {:type :confirm
                            :title (tr "modals.delete-file-confirm.title")
                            :message (tr "modals.delete-file-confirm.message")
                            :accept-label (tr "modals.delete-file-confirm.accept")
                            :on-accept on-delete-accept}))))))

        on-move-success
        (fn [team-id project-id]
          (if multi?
            (st/emit! (ntf/success (tr "dashboard.success-move-files")))
            (st/emit! (ntf/success (tr "dashboard.success-move-file"))))
          (if (or navigate (not= team-id current-team-id))
            (st/emit! (dcm/go-to-dashboard-files
                       {:project-id project-id
                        :team-id team-id}))
            (st/emit! (dd/fetch-recent-files team-id)
                      (dd/clear-selected-files))))

        on-move-accept
        (fn [params team-id project-id]
          (st/emit! (dd/move-files
                     (with-meta params
                       {:on-success #(on-move-success team-id project-id)}))))

        on-move
        (fn [team-id project-id]
          (let [params  {:ids (into #{} (map :id) files)
                         :project-id project-id}]
            (fn []

              (let [num-shared (filter #(:is-shared %) files)]
                (if (and (< 0 (count num-shared))
                         (not= team-id current-team-id))
                  (st/emit! (modal/show
                             {:type :delete-shared-libraries
                              :origin :move
                              :ids (into #{} (map :id) files)
                              :on-accept #(on-move-accept params team-id project-id)
                              :count-libraries (count num-shared)}))

                  (on-move-accept params team-id project-id))))))

        add-shared
        (fn []
          (st/emit! (dd/set-file-shared (assoc file :is-shared true))))

        del-shared
        (fn [_]
          (run! #(st/emit! (dd/set-file-shared (assoc % :is-shared false))) files))

        on-add-shared
        (fn [event]
          (dom/stop-propagation event)
          (st/emit! (dcm/show-shared-dialog (:id file) add-shared)))

        on-del-shared
        (fn [event]
          (dom/prevent-default event)
          (dom/stop-propagation event)
          (st/emit! (modal/show
                     {:type :delete-shared-libraries
                      :origin :unpublish
                      :ids (into #{} (map :id) files)
                      :on-accept del-shared
                      :count-libraries file-count})))

        on-export-binary-files
        (fn []
          (st/emit! (-> (fexp/open-export-dialog files)
                        (with-meta {::ev/origin "dashboard"}))))

        restore-fn
        (fn [_]
          (st/emit! (dd/restore-files-immediately
                     (with-meta {:team-id current-team-id
                                 :ids (into #{} d/xf:map-id files)}
                       {:on-success #(st/emit! (ntf/success (tr "dashboard.restore-success-notification" (:name file)))
                                               (dd/fetch-projects current-team-id)
                                               (dd/fetch-deleted-files current-team-id))
                        :on-error #(st/emit! (ntf/error (tr "dashboard.errors.error-on-restore-file" (:name file))))}))))

        on-restore-immediately
        (fn []
          (prn files)
          (st/emit!
           (modal/show {:type :confirm
                        :title (tr "dashboard-restore-file-confirmation.title")
                        :message (tr "dashboard-restore-file-confirmation.description" (:name file))
                        :accept-label (tr "labels.continue")
                        :accept-style :primary
                        :on-accept restore-fn})))

        on-delete-immediately
        (fn []
          (let [accept-fn #(st/emit! (dd/delete-files-immediately
                                      {:team-id current-team-id
                                       :ids (into #{} d/xf:map-id files)}))]
            (st/emit!
             (modal/show {:type :confirm
                          :title (tr "dashboard.delete-forever-confirmation.title")
                          :message (tr "dashboard.delete-file-forever-confirmation.description" (:name file))
                          :accept-label (tr "dashboard.delete-forever-confirmation.title")
                          :on-accept accept-fn}))))]

    (mf/with-effect []
      (->> (rp/cmd! :get-all-projects)
           (rx/map group-by-team)
           (rx/subs! #(reset! teams* %))))

    (mf/with-effect [on-close]
      (st/emit! (ptk/data-event :dropdown/open {:id "file-menu"}))
      (let [stream (->> st/stream
                        (rx/filter (ptk/type? :dropdown/open))
                        (rx/map deref)
                        (rx/filter #(not= "file-menu" (:id %)))
                        (rx/take 1))
            subs   (rx/subs! nil nil on-close stream)]
        (fn []
          (rx/dispose! subs))))

    (let [sub-options
          (concat
           (for [project current-projects]
             {:name (get-project-name project)
              :id (get-project-id project)
              :handler (on-move current-team-id (:id project))})
           (when (seq other-teams)
             [{:name (tr "dashboard.move-to-other-team")
               :id "move-to-other-team"
               :options
               (for [team other-teams]
                 {:name (get-team-name team)
                  :id (get-project-id team)
                  :options
                  (for [sub-project (:projects team)]
                    {:name (get-project-name sub-project)
                     :id (get-project-id sub-project)
                     :handler (on-move (:id team)
                                       (:id sub-project))})})}]))

          options
          (if can-restore
            [{:name    (tr "dashboard.file-menu.restore-files-option" (i18n/c file-count))
              :id      "restore-file"
              :handler on-restore-immediately}
             {:name    (tr "dashboard.file-menu.delete-files-permanently-option" (i18n/c file-count))
              :id      "delete-file"
              :handler on-delete-immediately}]
            (if multi?
              [(when can-edit
                 {:name    (tr "dashboard.duplicate-multi" file-count)
                  :id      "duplicate-multi"
                  :handler on-duplicate})

               (when (and (or (seq current-projects) (seq other-teams)) can-edit)
                 {:name    (tr "dashboard.move-to-multi" file-count)
                  :id      "file-move-multi"
                  :options    sub-options})

               {:name    (tr "dashboard.export-binary-multi" file-count)
                :id      "file-binary-export-multi"
                :handler on-export-binary-files}

               (when (and (:is-shared file) can-edit)
                 {:name    (tr "labels.unpublish-multi-files" file-count)
                  :id      "file-unpublish-multi"
                  :handler on-del-shared})

               (when (and (not is-lib-page?) can-edit)
                 {:name    :separator}
                 {:name    (tr "labels.delete-multi-files" file-count)
                  :id      "file-delete-multi"
                  :handler on-delete})]

              [{:name    (tr "dashboard.open-in-new-tab")
                :id      "file-open-new-tab"
                :handler on-new-tab}
               (when (and (not is-search-page?) can-edit)
                 {:name    (tr "labels.rename")
                  :id      "file-rename"
                  :handler on-edit})

               (when (and (not is-search-page?) can-edit)
                 {:name    (tr "dashboard.duplicate")
                  :id      "file-duplicate"
                  :handler on-duplicate})

               (when (and (not is-lib-page?)
                          (not is-search-page?)
                          (or (seq current-projects) (seq other-teams))
                          can-edit)
                 {:name    (tr "dashboard.move-to")
                  :id      "file-move-to"
                  :options sub-options})

               (when (and (not is-search-page?)
                          can-edit)
                 (if (:is-shared file)
                   {:name    (tr "dashboard.unpublish-shared")
                    :id      "file-del-shared"
                    :handler on-del-shared}
                   {:name    (tr "dashboard.add-shared")
                    :id      "file-add-shared"
                    :handler on-add-shared}))

               {:name   :separator}

               {:name    (tr "dashboard.download-binary-file")
                :id      "download-binary-file"
                :handler on-export-binary-files}

               (when (and (not is-lib-page?) (not is-search-page?) can-edit)
                 {:name   :separator})

               (when (and (not is-lib-page?) (not is-search-page?) can-edit)
                 {:name    (tr "labels.delete")
                  :id      "file-delete"
                  :handler on-delete})]))]

      [:> context-menu*
       {:on-close on-close
        :fixed (or (not= top 0) (not= left 0))
        :show true
        :min-width true
        :top top
        :left left
        :options options
        :origin parent-id}])))
