;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.project-menu
  (:require
   [app.main.data.common :as dcm]
   [app.main.data.dashboard :as dd]
   [app.main.data.modal :as modal]
   [app.main.data.notifications :as ntf]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.context-menu-a11y :refer [context-menu*]]
   [app.main.ui.context :as ctx]
   [app.main.ui.dashboard.import :as udi]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

(mf/defc project-menu*
  {::mf/props :obj}
  [{:keys [project show on-edit on-menu-close top left on-import]}]
  (let [top  (or top 0)
        left (or left 0)

        current-team-id (mf/use-ctx ctx/current-team-id)
        teams           (mf/deref refs/teams)
        teams           (-> teams (dissoc current-team-id) vals vec)

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
        #(st/emit! (dd/toggle-project-pin project))

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
        #(st/emit!
          (modal/show
           {:type :confirm
            :title (tr "modals.delete-project-confirm.title")
            :message (tr "modals.delete-project-confirm.message")
            :accept-label (tr "modals.delete-project-confirm.accept")
            :on-accept delete-fn}))

        file-input (mf/use-ref nil)

        on-import-files
        (mf/use-callback
         (fn []
           (dom/click (mf/ref-val file-input))))

        on-finish-import
        (mf/use-callback
         (fn []
           (when (fn? on-import) (on-import))))

        options
        [(when-not (:is-default project)
           {:name   (tr "labels.rename")
            :id     "project-rename"
            :handler on-edit})
         (when-not (:is-default project)
           {:name (tr "dashboard.duplicate")
            :id   "project-duplicate"
            :handler on-duplicate})
         (when-not (:is-default project)
           {:name (tr "dashboard.pin-unpin")
            :id   "project-pin"
            :handler toggle-pin})

         (when (and (seq teams) (not (:is-default project)))
           {:name    (tr "dashboard.move-to")
            :id      "project-move-to"
            :options (for [team teams]
                       {:name    (:name team)
                        :id      (str "move-to-" (:id team))
                        :handler (on-move (:id team))})})

         (when (some? on-import)
           {:name    (tr "dashboard.import")
            :id      "file-import"
            :handler on-import-files})
         (when-not (:is-default project)
           {:name :separator})
         (when-not (:is-default project)
           {:name    (tr "labels.delete")
            :id      "project-delete"
            :handler on-delete})]]

    [:*
     [:> context-menu*
      {:on-close on-menu-close
       :show show
       :fixed (or (not= top 0) (not= left 0))
       :min-width true
       :top top
       :left left
       :options options}]
     [:& udi/import-form {:ref file-input
                          :project-id (:id project)
                          :on-finish-import on-finish-import}]]))


