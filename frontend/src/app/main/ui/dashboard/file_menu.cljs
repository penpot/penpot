;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.file-menu
  (:require
   [app.common.data :as d]
   [app.main.data.dashboard :as dd]
   [app.main.data.events :as ev]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.context :as ctx]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [potok.core :as ptk]
   [rumext.alpha :as mf]))

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
  [{:keys [files show? on-edit on-menu-close top left navigate?] :as props}]
  (assert (seq files) "missing `files` prop")
  (assert (boolean? show?) "missing `show?` prop")
  (assert (fn? on-edit) "missing `on-edit` prop")
  (assert (fn? on-menu-close) "missing `on-menu-close` prop")
  (assert (boolean? navigate?) "missing `navigate?` prop")
  (let [top              (or top 0)
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
          (let [pparams {:project-id (:project-id file)
                         :file-id (:id file)}
                qparams {:page-id (first (get-in file [:data :pages]))}]
            (st/emit! (rt/nav-new-window :workspace pparams qparams))))

        on-duplicate
        (fn [_]
          (apply st/emit! (map dd/duplicate-file files))
          (st/emit! (dm/success (tr "dashboard.success-duplicate-file"))))

        delete-fn
        (fn [_]
          (apply st/emit! (map dd/delete-file files))
          (st/emit! (dm/success (tr "dashboard.success-delete-file"))))

        on-delete
        (fn [event]
          (dom/stop-propagation event)
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
                        :on-accept delete-fn}))))

        on-move-success
        (fn [team-id project-id]
          (if multi?
            (st/emit! (dm/success (tr "dashboard.success-move-files")))
            (st/emit! (dm/success (tr "dashboard.success-move-file"))))
          (if (or navigate? (not= team-id current-team-id))
            (st/emit! (dd/go-to-files team-id project-id))
            (st/emit! (dd/fetch-recent-files)
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
        (st/emitf (dd/set-file-shared (assoc file :is-shared true)))

        del-shared
        (st/emitf (dd/set-file-shared (assoc file :is-shared false)))

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
          (st/emit! (modal/show
                     {:type :confirm
                      :message ""
                      :title (tr "modals.remove-shared-confirm.message" (:name file))
                      :hint (tr "modals.remove-shared-confirm.hint")
                      :cancel-label :omit
                      :accept-label (tr "modals.remove-shared-confirm.accept")
                      :on-accept del-shared})))

        on-export-files
        (mf/use-callback
         (mf/deps files current-team-id)
         (fn [_]
           (st/emit! (ptk/event ::ev/event {::ev/name "export-files"
                                            :num-files (count files)}))
           (->> (rx/from files)
                (rx/flat-map
                 (fn [file]
                   (->> (rp/query :file-libraries {:file-id (:id file)})
                        (rx/map #(assoc file :has-libraries? (d/not-empty? %))))))
                (rx/reduce conj [])
                (rx/subs
                 (fn [files]
                   (st/emit!
                    (modal/show
                     {:type :export
                      :team-id current-team-id
                      :has-libraries? (->> files (some :has-libraries?))
                      :files files})))))))

        ;; NOTE: this is used for detect if component is still mounted
        mounted-ref (mf/use-ref true)]

    (mf/use-effect
     (mf/deps show?)
     (fn []
       (when show?
         (->> (rp/query! :all-projects)
              (rx/map group-by-team)
              (rx/subs #(when (mf/ref-val mounted-ref)
                          (reset! teams %)))))))

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
                                                (:id sub-project))])])]))

            options (if multi?
                      [[(tr "dashboard.duplicate-multi" file-count) on-duplicate]
                       (when (or (seq current-projects) (seq other-teams))
                         [(tr "dashboard.move-to-multi" file-count) nil sub-options])
                       [(tr "dashboard.export-multi" file-count) on-export-files]
                       [:separator]
                       [(tr "labels.delete-multi-files" file-count) on-delete]]

                      [[(tr "dashboard.open-in-new-tab") on-new-tab]
                       [(tr "labels.rename") on-edit]
                       [(tr "dashboard.duplicate") on-duplicate]
                       (when (or (seq current-projects) (seq other-teams))
                           [(tr "dashboard.move-to") nil sub-options])
                       (if (:is-shared file)
                         [(tr "dashboard.remove-shared") on-del-shared]
                         [(tr "dashboard.add-shared") on-add-shared])
                       [(tr "dashboard.export-single") on-export-files]
                       [:separator]
                       [(tr "labels.delete") on-delete]])]

          [:& context-menu {:on-close on-menu-close
                            :show show?
                            :fixed? (or (not= top 0) (not= left 0))
                            :min-width? true
                            :top top
                            :left left
                            :options options}]))))
