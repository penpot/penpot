;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.dashboard.project-menu
  (:require
   [app.main.data.common :as dcm]
   [app.main.data.dashboard :as dd]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.ds.layout.menu :refer [menu* menu-item* menu-separator* sub-menu*]]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; The menu items only, with no popover of their own — shared by project-menu*
;; below (opened from the "..." button, via Menu) and by projects.cljs's own
;; right-click handling (via ContextMenu), so both triggers show the exact
;; same options.
;;
;; on-import-click, rather than this owning its own file input/ref: the
;; popover this renders inside really unmounts its content on close (unlike
;; the old context-menu-a11y, which just hid it), and selecting "Import"
;; closes the menu in the same tick — so a ref owned here can already be
;; gone by the time its own click handler would fire. The caller keeps the
;; hidden input mounted for as long as the row itself exists instead.
(mf/defc project-menu-items*
  {::mf/private true}
  [{:keys [project on-edit on-import-click]}]

  (assert (some? project) "missing `project` prop")
  (assert (fn? on-edit) "missing `on-edit` prop")

  (let [is-default?      (:is-default project)

        current-team-id  (mf/use-ctx ctx/current-team-id)
        teams            (mf/deref refs/teams)
        other-teams      (-> teams (dissoc current-team-id) vals)

        on-duplicate-success
        (fn [new-project]
          (st/emit! (ntf/success (tr "dashboard.success-duplicate-project"))
                    (dcm/go-to-dashboard-files
                     :team-id (:team-id new-project)
                     :project-id (:id new-project))))

        on-duplicate
        (fn []
          (st/emit! (dd/duplicate-project
                     (with-meta project {:on-success on-duplicate-success}))))

        toggle-pin
        (fn []
          (st/emit! (dd/toggle-project-pin project)))

        on-move-success
        (fn [team-id]
          (st/emit! (dcm/go-to-dashboard-recent :team-id team-id)))

        on-move
        (fn [team-id]
          (let [data  {:id (:id project) :team-id team-id}
                mdata {:on-success #(on-move-success team-id)}]
            #(st/emit! (ntf/success (tr "dashboard.success-move-project"))
                       (dd/move-project (with-meta data mdata)))))

        delete-fn
        (fn [_]
          (let [team-id (:team-id project)]
            (st/emit! (ntf/success (tr "dashboard.success-delete-project"))
                      (dd/delete-project project)
                      (dcm/go-to-dashboard-recent :team-id team-id))))

        on-delete
        (fn []
          (st/emit!
           (modal/show {:type :confirm
                        :title (tr "modals.delete-project-confirm.title")
                        :message (tr "modals.delete-project-confirm.message")
                        :accept-label (tr "modals.delete-project-confirm.accept")
                        :on-accept delete-fn})))]

    [:*
     (when-not is-default?
       [:> menu-item* {:id "project-rename" :on-action on-edit}
        (tr "labels.rename")])

     (when-not is-default?
       [:> menu-item* {:id "project-duplicate" :on-action on-duplicate}
        (tr "dashboard.duplicate")])

     (when-not is-default?
       [:> menu-item* {:id "project-pin" :on-action toggle-pin}
        (tr "dashboard.pin-unpin")])

     (when (and (seq other-teams) (not is-default?))
       [:> sub-menu* {:id "project-move-to" :trigger (tr "dashboard.move-to") :variant "drilldown"}
        (for [team other-teams]
          [:> menu-item* {:key (:id team)
                          :id (str "move-to-" (:id team))
                          :on-action (on-move (:id team))}
           (:name team)])])

     (when (some? on-import-click)
       [:> menu-item* {:id "file-import" :on-action on-import-click}
        (tr "dashboard.import")])

     (when-not is-default?
       [:*
        [:> menu-separator*]
        [:> menu-item* {:id "project-delete" :on-action on-delete}
         (tr "labels.delete")]])]))

(mf/defc project-menu*
  [{:keys [project is-open on-open-change on-edit on-import-click trigger]}]
  [:> menu*
   {:is-open is-open
    :on-open-change on-open-change
    :placement "bottom end"
    :trigger trigger}
   [:> project-menu-items* {:project project
                            :on-edit on-edit
                            :on-import-click on-import-click}]])
