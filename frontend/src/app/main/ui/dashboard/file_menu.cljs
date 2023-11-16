;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.file-menu
  (:require
   [app.main.data.common :as dcm]
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.context-menu-a11y :refer [context-menu-a11y]]
   [app.main.ui.context :as ctx]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(defn get-project-name
  [project]
  (if (:is-default project)
    (tr "labels.drafts")
    (:name project)))

(defn get-project-id
  [project]
  (str (:id project)))

(defn get-team-name
  [team]
  (if (:is-default team)
    (tr "dashboard.your-penpot")
    (:name team)))

(defn group-by-team
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

(mf/defc file-menu
  [{:keys [files show? on-edit on-menu-close top left navigate? origin parent-id] :as props}]
  (assert (seq files) "missing `files` prop")
  (assert (boolean? show?) "missing `show?` prop")
  (assert (fn? on-edit) "missing `on-edit` prop")
  (assert (fn? on-menu-close) "missing `on-menu-close` prop")
  (assert (boolean? navigate?) "missing `navigate?` prop")
  (let [is-lib-page?     (= :libraries origin)
        is-search-page?  (= :search origin)
        top              (or top 0)
        left             (or left 0)

        file             (first files)
        file-count       (count files)
        multi?           (> file-count 1)

        current-team-id  (mf/use-ctx ctx/current-team-id)
        teams            (mf/use-state nil)
        current-team     (get @teams current-team-id)
        other-teams      (remove #(= (:id %) current-team-id) (vals @teams))
        current-projects (remove #(= (:id %) (:project-id file))
                                 (:projects current-team))
        on-new-tab
        (fn [_]
          (let [path-params  {:project-id (:project-id file)
                              :file-id (:id file)}]
            (st/emit! (rt/nav-new-window* {:rname :workspace
                                           :path-params path-params}))))

        on-duplicate
        (fn [_]
          (apply st/emit! (map dd/duplicate-file files))
          (st/emit! (dm/success (tr "dashboard.success-duplicate-file" (i18n/c (count files))))))

        delete-fn
        (fn [_]
          (apply st/emit! (map dd/delete-file files))
          (st/emit! (dm/success (tr "dashboard.success-delete-file" (i18n/c (count files))))))

        on-delete
        (fn [event]
          (dom/stop-propagation event)

          (let [num-shared (filter #(:is-shared %) files)]

            (if (< 0 (count num-shared))
              (st/emit! (modal/show
                         {:type :delete-shared-libraries
                          :origin :delete
                          :ids (into #{} (map :id) files)
                          :on-accept delete-fn
                          :count-libraries (count num-shared)}))

              (if multi?
                (st/emit! (modal/show
                           {:type :confirm
                            :title (tr "modals.delete-file-multi-confirm.title" file-count)
                            :message (tr "modals.delete-file-multi-confirm.message" file-count)
                            :accept-label (tr "modals.delete-file-multi-confirm.accept" file-count)
                            :on-accept delete-fn}))
                (st/emit! (modal/show
                           {:type :confirm
                            :title (tr "modals.delete-file-confirm.title")
                            :message (tr "modals.delete-file-confirm.message")
                            :accept-label (tr "modals.delete-file-confirm.accept")
                            :on-accept delete-fn}))))))

        on-move-success
        (fn [team-id project-id]
          (if multi?
            (st/emit! (dm/success (tr "dashboard.success-move-files")))
            (st/emit! (dm/success (tr "dashboard.success-move-file"))))
          (if (or navigate? (not= team-id current-team-id))
            (st/emit! (dd/go-to-files team-id project-id))
            (st/emit! (dd/fetch-recent-files team-id)
                      (dd/clear-selected-files))))

        on-move
        (fn [team-id project-id]
          (let [params  {:ids (into #{} (map :id) files)
                         :project-id project-id}]
            (fn []
              (st/emit! (dd/move-files
                         (with-meta params
                           {:on-success #(on-move-success team-id project-id)}))))))

        add-shared
        #(st/emit! (dd/set-file-shared (assoc file :is-shared true)))

        del-shared
        (mf/use-fn
         (mf/deps files)
         (fn [_]
           (run! #(st/emit! (dd/set-file-shared (assoc % :is-shared false))) files)))

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
        (mf/use-fn
         (mf/deps files)
         (fn [binary?]
           (let [evname (if binary?
                          "export-binary-files"
                          "export-standard-files")]
             (st/emit! (ptk/event ::ev/event {::ev/name evname
                                              ::ev/origin "dashboard"
                                              :num-files (count files)})
                       (dcm/export-files files binary?)))))

        on-export-binary-files
        (mf/use-fn
         (mf/deps on-export-files)
         (partial on-export-files true))

        on-export-standard-files
        (mf/use-fn
         (mf/deps on-export-files)
         (partial on-export-files false))

        ;; NOTE: this is used for detect if component is still mounted
        mounted-ref (mf/use-ref true)]

    (mf/use-effect
     (mf/deps show?)
     (fn []
       (when show?
         (->> (rp/cmd! :get-all-projects)
              (rx/map group-by-team)
              (rx/subs #(when (mf/ref-val mounted-ref)
                          (reset! teams %)))))))

    (when current-team
      (let [sub-options (concat (vec (for [project current-projects]
                                       {:option-name (get-project-name project)
                                        :id (get-project-id project)
                                        :option-handler (on-move (:id current-team)
                                                                 (:id project))}))
                                (when (seq other-teams)
                                  [{:option-name (tr "dashboard.move-to-other-team")
                                    :id "move-to-other-team"
                                    :sub-options
                                    (for [team other-teams]
                                      {:option-name (get-team-name team)
                                       :id (get-project-id team)
                                       :sub-options
                                       (for [sub-project (:projects team)]
                                         {:option-name (get-project-name sub-project)
                                          :id (get-project-id sub-project)
                                          :option-handler (on-move (:id team)
                                                                   (:id sub-project))})})}]))

            options (if multi?
                      [{:option-name    (tr "dashboard.duplicate-multi" file-count)
                        :id             "file-duplicate-multi"
                        :option-handler on-duplicate
                        :data-test      "duplicate-multi"}
                       (when (or (seq current-projects) (seq other-teams))
                         {:option-name    (tr "dashboard.move-to-multi" file-count)
                          :id             "file-move-multi"
                          :sub-options    sub-options
                          :data-test      "move-to-multi"})
                       {:option-name    (tr "dashboard.export-binary-multi" file-count)
                        :id             "file-binari-export-multi"
                        :option-handler on-export-binary-files}
                       {:option-name    (tr "dashboard.export-standard-multi" file-count)
                        :id             "file-standard-export-multi"
                        :option-handler on-export-standard-files}
                       (when (:is-shared file)
                         {:option-name    (tr "labels.unpublish-multi-files" file-count)
                          :id             "file-unpublish-multi"
                          :option-handler on-del-shared
                          :data-test      "file-del-shared"})
                       (when (not is-lib-page?)
                         {:option-name    :separator}
                         {:option-name    (tr "labels.delete-multi-files" file-count)
                          :id             "file-delete-multi"
                          :option-handler on-delete
                          :data-test      "delete-multi-files"})]

                      [{:option-name    (tr "dashboard.open-in-new-tab")
                        :id             "file-open-new-tab"
                        :option-handler on-new-tab}
                       (when (not is-search-page?)
                         {:option-name    (tr "labels.rename")
                          :id             "file-rename"
                          :option-handler on-edit
                          :data-test      "file-rename"})
                       (when (not is-search-page?)
                         {:option-name    (tr "dashboard.duplicate")
                          :id             "file-duplicate"
                          :option-handler on-duplicate
                          :data-test      "file-duplicate"})
                       (when (and (not is-lib-page?) (not is-search-page?) (or (seq current-projects) (seq other-teams)))
                         {:option-name    (tr "dashboard.move-to")
                          :id             "file-move-to"
                          :sub-options    sub-options
                          :data-test      "file-move-to"})
                       (when (not is-search-page?)
                         (if (:is-shared file)
                           {:option-name    (tr "dashboard.unpublish-shared")
                            :id             "file-del-shared"
                            :option-handler on-del-shared
                            :data-test      "file-del-shared"}
                           {:option-name    (tr "dashboard.add-shared")
                            :id             "file-add-shared"
                            :option-handler on-add-shared
                            :data-test      "file-add-shared"}))
                       {:option-name   :separator}
                       {:option-name    (tr "dashboard.download-binary-file")
                        :id             "file-download-binary"
                        :option-handler on-export-binary-files
                        :data-test      "download-binary-file"}
                       {:option-name    (tr "dashboard.download-standard-file")
                        :id             "file-download-standard"
                        :option-handler on-export-standard-files
                        :data-test      "download-standard-file"}
                       (when (and (not is-lib-page?) (not is-search-page?))
                         {:option-name   :separator}
                         {:option-name    (tr "labels.delete")
                          :id             "file-delete"
                          :option-handler on-delete
                          :data-test      "file-delete"})])]

        [:& context-menu-a11y {:on-close on-menu-close
                               :show show?
                               :fixed? (or (not= top 0) (not= left 0))
                               :min-width? true
                               :top top
                               :left left
                               :options options
                               :origin parent-id
                               :workspace? false}]))))
