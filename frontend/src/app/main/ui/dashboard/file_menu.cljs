;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.file-menu
  (:require
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
   [app.util.timers :as tm]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [rumext.v2 :as mf]))

(defn get-project-name
  [project]
  (if (:is-default project)
    (tr "labels.drafts")
    (:name project)))

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
  [{:keys [files show? on-edit on-menu-close top left navigate? origin] :as props}]
  (assert (seq files) "missing `files` prop")
  (assert (boolean? show?) "missing `show?` prop")
  (assert (fn? on-edit) "missing `on-edit` prop")
  (assert (fn? on-menu-close) "missing `on-menu-close` prop")
  (assert (boolean? navigate?) "missing `navigate?` prop")
  (let [is-lib-page? (= :libraries origin)
        top              (or top 0)
        left             (or left 0)

        file             (first files)
        file-count       (count files)
        multi?           (> file-count 1)

        current-team-id  (mf/use-ctx ctx/current-team-id)
        teams            (mf/use-state nil)
        current-team     (get @teams current-team-id)
        _ (prn "current-team" current-team)
        other-teams      (remove #(= (:id %) current-team-id) (vals @teams))

        _ (prn "other-teams" other-teams)
        current-projects (remove #(= (:id %) (:project-id file))
                                 (:projects current-team))
        _ (prn "current-projects" current-projects)
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
              (do (st/emit! (dd/fetch-libraries-using-files files))
                  (st/emit! (modal/show
                             {:type :delete-shared
                              :origin :delete
                              :on-accept delete-fn
                              :count-libraries (count num-shared)})))

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
          (let [params  {:ids (set (map :id files))
                         :project-id project-id}]
            (fn []
              (st/emit! (dd/move-files
                         (with-meta params
                           {:on-success #(on-move-success team-id project-id)}))))))

        add-shared
        #(st/emit! (dd/set-file-shared (assoc file :is-shared true)))

        del-shared
        #(st/emit! (dd/set-file-shared (assoc file :is-shared false)))

        on-add-shared
        (fn [event]
          (dom/stop-propagation event)
          (st/emit! (modal/show
                     {:type :confirm
                      :message ""
                      :title (tr "modals.add-shared-confirm.message" (:name file))
                      :hint (tr "modals.add-shared-confirm.hint")
                      :cancel-label :omit
                      :accept-label (tr "modals.add-shared-confirm.accept")
                      :accept-style :primary
                      :on-accept add-shared})))

        on-del-shared
        (fn [event]
          (dom/prevent-default event)
          (dom/stop-propagation event)
          (st/emit! (dd/fetch-libraries-using-files files))
          (st/emit! (modal/show
                     {:type :delete-shared
                      :origin :unpublish
                      :on-accept del-shared
                      :count-libraries file-count})))

        on-export-files
        (fn [event-name binary?]
          (st/emit! (ptk/event ::ev/event {::ev/name event-name
                                           ::ev/origin "dashboard"
                                           :num-files (count files)}))

          (->> (rx/from files)
               (rx/flat-map
                (fn [file]
                  (->> (rp/command :has-file-libraries {:file-id (:id file)})
                       (rx/map #(assoc file :has-libraries? %)))))
               (rx/reduce conj [])
               (rx/subs
                (fn [files]
                  (st/emit!
                   (modal/show
                    {:type :export
                     :team-id current-team-id
                     :has-libraries? (->> files (some :has-libraries?))
                     :files files
                     :binary? binary?}))))))

        on-export-binary-files
        (mf/use-callback
         (mf/deps files current-team-id)
         (fn [_]
           (on-export-files "export-binary-files" true)))

        on-export-standard-files
        (mf/use-callback
         (mf/deps files current-team-id)
         (fn [_]
           (on-export-files "export-standard-files" false)))

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
    
    (mf/with-effect [multi?]
      (tm/schedule-on-idle 
       #(when-let [id (if multi?
                        "file-duplicate-multi"
                        "file-open-new-tab")]
          (prn id)
          (.log js/console (clj->js (dom/get-element id)))
          (dom/focus! (dom/get-element id)))))

    (when current-team
      (let [sub-options (conj (vec (for [project current-projects]
                                     [(get-project-name project)
                                      (on-move (:id current-team)
                                               (:id project))]))
                              (when (seq other-teams)
                                [(tr "dashboard.move-to-other-team") nil
                                 (for [team other-teams]
                                   [(get-team-name team) nil
                                    (for [sub-project (:projects team)]
                                      [(get-project-name sub-project)
                                       (on-move (:id team)
                                                (:id sub-project))])])
                                 "move-to-other-team"]))


            options (if multi?
                       [{:option-name    (tr "dashboard.duplicate-multi" file-count) 
                         :id             "file-duplicate-multi" 
                         :option-handler on-duplicate 
                         :sub-options    nil 
                         :data-test      "duplicate-multi"}
                        (when (or (seq current-projects) (seq other-teams))
                          {:option-name    (tr "dashboard.move-to-multi" file-count) 
                           :id             "file-move-multi" 
                           :option-handler nil 
                           :sub-options    sub-options 
                           :data-test      "move-to-multi"})
                        {:option-name    (tr "dashboard.export-binary-multi" file-count)
                         :id             "file-binari-export-multi" 
                         :option-handler on-export-binary-files 
                         :sub-options    nil 
                         :data-test      nil}
                        {:option-name    (tr "dashboard.export-standard-multi" file-count) 
                         :id             "file-standard-export-multi" 
                         :option-handler on-export-standard-files 
                         :sub-options    nil 
                         :data-test      nil}
                        (when (:is-shared file)
                          {:option-name    (tr "labels.unpublish-multi-files" file-count)
                           :id             "file-unpublish-multi"
                           :option-handler on-del-shared
                           :sub-options    nil
                           :data-test      "file-del-shared"})
                        (when (not is-lib-page?)
                          {:option-name    :separator
                           :id             nil
                           :option-handler nil
                           :sub-options    nil
                           :data-test      nil}
                          {:option-name    (tr "labels.delete-multi-files" file-count)
                           :id             "file-delete-multi"
                           :option-handler on-delete
                           :sub-options    nil
                           :data-test      "delete-multi-files"})]

                       [{:option-name    (tr "dashboard.open-in-new-tab")
                         :id             "file-open-new-tab"
                         :option-handler on-new-tab
                         :sub-options    nil
                         :data-test      nil}
                        {:option-name    (tr "labels.rename")
                         :id             "file-rename"
                         :option-handler on-edit
                         :sub-options    nil
                         :data-test      "file-rename"}
                        {:option-name    (tr "dashboard.duplicate")
                         :id             "file-duplicate"
                         :option-handler on-duplicate
                         :sub-options    nil
                         :data-test      "file-duplicate"}
                        (when (and (not is-lib-page?) (or (seq current-projects) (seq other-teams)))
                          {:option-name    (tr "dashboard.move-to")
                           :id             "file-move-to"
                           :option-handler nil
                           :sub-options    sub-options
                           :data-test      "file-move-to"})
                        (if (:is-shared file)
                          {:option-name    (tr "dashboard.unpublish-shared")
                           :id             "file-del-shared"
                           :option-handler on-del-shared
                           :sub-options    nil
                           :data-test      "file-del-shared"}
                          {:option-name    (tr "dashboard.add-shared")
                           :id             "file-add-shared"
                           :option-handler on-add-shared
                           :sub-options    nil
                           :data-test      "file-add-shared"})
                        {:option-name   :separator
                         :id             nil
                         :option-handler nil
                         :sub-options    nil
                         :data-test      nil}
                        {:option-name    (tr "dashboard.download-binary-file")
                         :id             "file-download-binary"
                         :option-handler on-export-binary-files
                         :sub-options    nil
                         :data-test      "download-binary-file"}
                        {:option-name    (tr "dashboard.download-standard-file")
                         :id             "file-download-standard"
                         :option-handler on-export-standard-files
                         :sub-options    nil
                         :data-test      "download-standard-file"}
                        (when (not is-lib-page?)
                          {:option-name   :separator
                           :id             nil
                           :option-handler nil
                           :sub-options    nil
                           :data-test      nil}
                          {:option-name    (tr "labels.delete")
                           :id             "file-delete"
                           :option-handler on-delete
                           :sub-options    nil
                           :data-test      "file-delete"})])]

        [:& context-menu-a11y {:on-close on-menu-close
                               :show show?
                               :fixed? (or (not= top 0) (not= left 0))
                               :min-width? true
                               :top top
                               :left left
                               :options options}]))))
