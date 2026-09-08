;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

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
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.layout.menu :refer [menu* menu-item* menu-separator* sub-menu*]]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
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
    (tr "dashboard.personal-projects")
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

;; The "move to" tree can be arbitrarily deep (current team's projects, then
;; every other team's own projects), so every level here uses SubMenu's
;; drilldown variant instead of a flyout: opening a chain of flyouts that
;; deep would run off-screen well before it ran out of teams.
(mf/defc move-to-items*
  {::mf/private true}
  [{:keys [current-projects other-teams current-team-id on-move]}]
  [:*
   (for [project current-projects]
     [:> menu-item* {:key (get-project-id project)
                     :id (get-project-id project)
                     :on-action (on-move current-team-id (:id project))}
      (get-project-name project)])

   (when (seq other-teams)
     [:> sub-menu* {:key "move-to-other-team"
                    :id "move-to-other-team"
                    :trigger (tr "dashboard.move-to-other-team")
                    :variant "drilldown"}
      (for [team other-teams]
        [:> sub-menu* {:key (get-project-id team)
                       :id (get-project-id team)
                       :trigger (get-team-name team)
                       :variant "drilldown"}
         (for [sub-project (:projects team)]
           [:> menu-item* {:key (get-project-id sub-project)
                           :id (get-project-id sub-project)
                           :on-action (on-move (:id team) (:id sub-project))}
            (get-project-name sub-project)])])])])

;; The menu items only, with no popover of their own — shared by file-menu*
;; below (opened from the "..." button, via Menu) and by grid.cljs's own
;; right-click handling (via ContextMenu), so both triggers show the exact
;; same options.
;; The popover this renders inside only mounts file-menu-items* while it's
;; open, so a plain component-local fetch would re-run — and start every
;; single open from an empty "Move to" state — every time. Caching the last
;; response here means only the first open of a session (per tab) pays that
;; cost; later opens render with the previous list immediately while a
;; fresh fetch updates it in the background.
(defonce ^:private teams-cache (atom nil))

(mf/defc file-menu-items*
  [{:keys [files on-edit navigate origin can-edit can-restore]}]

  (assert (seq files) "missing `files` prop")
  (assert (fn? on-edit) "missing `on-edit` prop")
  (assert (boolean? navigate) "missing `navigate` prop")

  (let [is-lib-page?     (= :libraries origin)
        is-search-page?  (= :search origin)

        file             (first files)
        file-count       (count files)
        multi?           (> file-count 1)

        current-team-id  (mf/use-ctx ctx/current-team-id)
        teams*           (mf/use-state #(deref teams-cache))
        teams            (deref teams*)

        current-team     (get teams current-team-id)
        other-teams      (remove #(= (:id %) current-team-id) (vals teams))
        file-project-ids (into #{} (map :project-id) files)
        current-projects (remove #(contains? file-project-ids (:id %))
                                 (:projects current-team))

        on-new-tab
        (fn []
          (st/emit! (dcm/go-to-workspace
                     {:file-id (:id file)
                      ::rt/new-window true})))

        on-duplicate
        (fn []
          (apply st/emit! (map dd/duplicate-file files))
          (st/emit! (ntf/success (tr "dashboard.success-duplicate-file" (i18n/c file-count)))))

        on-delete-accept
        (fn [_]
          (apply st/emit! (map dd/delete-file files))
          (st/emit! (ntf/success (tr "dashboard.success-delete-file" (i18n/c file-count)))
                    (dd/clear-selected-files)))

        on-delete
        (fn []
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
        (fn []
          (st/emit! (dcm/show-shared-dialog (:id file) add-shared)))

        on-del-shared
        (fn []
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
           (rx/subs! #(do (reset! teams-cache %)
                          (reset! teams* %)))))

    (cond
      can-restore
      [:*
       [:> menu-item* {:id "restore-file" :on-action on-restore-immediately}
        (tr "dashboard.file-menu.restore-files-option" (i18n/c file-count))]
       [:> menu-item* {:id "delete-file" :on-action on-delete-immediately}
        (tr "dashboard.file-menu.delete-files-permanently-option" (i18n/c file-count))]]

      multi?
      [:*
       (when can-edit
         [:> menu-item* {:id "duplicate-multi" :on-action on-duplicate}
          (tr "dashboard.duplicate-multi" file-count)])

       (when (and (or (seq current-projects) (seq other-teams)) can-edit)
         [:> sub-menu* {:id "file-move-multi" :trigger (tr "dashboard.move-to-multi" file-count) :variant "drilldown"}
          [:> move-to-items* {:current-projects current-projects
                              :other-teams other-teams
                              :current-team-id current-team-id
                              :on-move on-move}]])

       [:> menu-item* {:id "file-binary-export-multi" :on-action on-export-binary-files}
        (tr "dashboard.export-binary-multi" file-count)]

       (when (and (:is-shared file) can-edit)
         [:> menu-item* {:id "file-unpublish-multi" :on-action on-del-shared}
          (tr "labels.unpublish-multi-files" file-count)])

       (when (and (not is-lib-page?) can-edit)
         [:*
          [:> menu-separator*]
          [:> menu-item* {:id "file-delete-multi" :on-action on-delete}
           (tr "labels.delete-multi-files" file-count)]])]

      :else
      [:*
       [:> menu-item* {:id "file-open-new-tab" :on-action on-new-tab}
        (tr "dashboard.open-in-new-tab")]

       (when (and (not is-search-page?) can-edit)
         [:> menu-item* {:id "file-rename" :on-action on-edit}
          (tr "labels.rename")])

       (when (and (not is-search-page?) can-edit)
         [:> menu-item* {:id "file-duplicate" :on-action on-duplicate}
          (tr "dashboard.duplicate")])

       (when (and (not is-lib-page?)
                  (not is-search-page?)
                  (or (seq current-projects) (seq other-teams))
                  can-edit)
         [:> sub-menu* {:id "file-move-to" :trigger (tr "dashboard.move-to") :variant "drilldown"}
          [:> move-to-items* {:current-projects current-projects
                              :other-teams other-teams
                              :current-team-id current-team-id
                              :on-move on-move}]])

       (when (and (not is-search-page?) can-edit)
         ;; Same id in both branches: :is-shared can flip while this menu
         ;; instance stays mounted (the on-add-shared/on-del-shared action
         ;; itself changes it), and react-stately's Collection requires an
         ;; item's id to stay stable across such an update rather than swap
         ;; to a differently-id'd item in the same slot.
         (if (:is-shared file)
           [:> menu-item* {:id "file-shared-toggle" :on-action on-del-shared}
            (tr "dashboard.unpublish-shared")]
           [:> menu-item* {:id "file-shared-toggle" :on-action on-add-shared}
            (tr "dashboard.add-shared")]))

       [:> menu-separator*]

       [:> menu-item* {:id "download-binary-file" :on-action on-export-binary-files}
        (tr "dashboard.download-binary-file")]

       (when (and (not is-lib-page?) (not is-search-page?) can-edit)
         [:*
          [:> menu-separator*]
          [:> menu-item* {:id "file-delete" :on-action on-delete}
           (tr "labels.delete")]])])))

(mf/defc file-menu*
  [{:keys [files on-edit is-open on-open-change trigger navigate origin can-edit can-restore]}]
  [:> menu*
   {:is-open is-open
    :on-open-change on-open-change
    :placement "bottom end"
    :trigger trigger}
   [:> file-menu-items* {:files files
                         :on-edit on-edit
                         :navigate navigate
                         :origin origin
                         :can-edit can-edit
                         :can-restore can-restore}]])
