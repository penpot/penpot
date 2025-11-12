;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.file-menu
  (:require
   [app.config :as cf]
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
  [{:keys [files on-edit on-close top left navigate origin parent-id can-edit]}]

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
          (st/emit! (ntf/success (tr "dashboard.success-duplicate-file" (i18n/c (count files))))))

        on-delete-accept
        (fn [_]
          (apply st/emit! (map dd/delete-file files))
          (st/emit! (ntf/success (tr "dashboard.success-delete-file" (i18n/c (count files))))
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

        on-export-files
        (fn [format]
          (st/emit! (with-meta (fexp/export-files files format)
                      {::ev/origin "dashboard"})))

        on-export-binary-files
        (partial on-export-files :binfile-v1)

        on-export-binary-files-v3
        (partial on-export-files :binfile-v3)

        on-export-standard-files
        (partial on-export-files :legacy-zip)]

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
              :handler (on-move (:id current-team)
                                (:id project))})
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
          (if multi?
            [(when can-edit
               {:name    (tr "dashboard.duplicate-multi" file-count)
                :id      "duplicate-multi"
                :handler on-duplicate})

             (when (and (or (seq current-projects) (seq other-teams)) can-edit)
               {:name    (tr "dashboard.move-to-multi" file-count)
                :id      "file-move-multi"
                :options    sub-options})

             (when-not (contains? cf/flags :export-file-v3)
               {:name    (tr "dashboard.export-binary-multi" file-count)
                :id      "file-binary-export-multi"
                :handler on-export-binary-files})

             (when (contains? cf/flags :export-file-v3)
               {:name    (tr "dashboard.export-binary-multi" file-count)
                :id      "file-binary-export-multi"
                :handler on-export-binary-files-v3})

             (when-not (contains? cf/flags :export-file-v3)
               {:name    (tr "dashboard.export-standard-multi" file-count)
                :id      "file-standard-export-multi"
                :handler on-export-standard-files})

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

             (when-not (contains? cf/flags :export-file-v3)
               {:name    (tr "dashboard.download-binary-file")
                :id      "download-binary-file"
                :handler on-export-binary-files})

             (when (contains? cf/flags :export-file-v3)
               {:name    (tr "dashboard.download-binary-file")
                :id      "download-binary-file"
                :handler on-export-binary-files-v3})

             (when-not (contains? cf/flags :export-file-v3)
               {:name    (tr "dashboard.download-standard-file")
                :id      "download-standard-file"
                :handler on-export-standard-files})

             (when (and (not is-lib-page?) (not is-search-page?) can-edit)
               {:name   :separator})

             (when (and (not is-lib-page?) (not is-search-page?) can-edit)
               {:name    (tr "labels.delete")
                :id      "file-delete"
                :handler on-delete})])]

      [:> context-menu*
       {:on-close on-close
        :fixed (or (not= top 0) (not= left 0))
        :show true
        :min-width true
        :top top
        :left left
        :options options
        :origin parent-id}])))
