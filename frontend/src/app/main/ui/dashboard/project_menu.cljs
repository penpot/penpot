;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.dashboard.project-menu
  (:require
   [app.common.spec :as us]
   [app.main.data.dashboard :as dd]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.context :as ctx]
   [app.main.ui.dashboard.import :as udi]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [cljs.spec.alpha :as s]
   [rumext.v2 :as mf]))

(s/def ::project some?)
(s/def ::show? boolean?)
(s/def ::on-edit fn?)
(s/def ::on-menu-close fn?)
(s/def ::top (s/nilable ::us/number))
(s/def ::left (s/nilable ::us/number))
(s/def ::on-import fn?)

(s/def ::project-menu
  (s/keys :req-un [::project ::show? ::on-edit ::on-menu-close]
          :opt-un [::top ::left ::on-import]))

(mf/defc project-menu
  [{:keys [project show? on-edit on-menu-close top left on-import] :as props}]
  (us/verify ::project-menu props)
  (let [top  (or top 0)
        left (or left 0)

        current-team-id (mf/use-ctx ctx/current-team-id)
        teams           (mf/deref refs/teams)
        teams           (-> teams (dissoc current-team-id) vals vec)

        on-duplicate-success
        (fn [new-project]
          (st/emit! (dm/success (tr "dashboard.success-duplicate-project"))
                    (rt/nav :dashboard-files
                            {:team-id (:team-id new-project)
                             :project-id (:id new-project)})))

        on-duplicate
        (fn []
          (st/emit! (dd/duplicate-project
                     (with-meta project {:on-success on-duplicate-success}))))

        toggle-pin
        #(st/emit! (dd/toggle-project-pin project))

        on-move-success
        (fn [team-id]
          (st/emit! (dd/go-to-projects team-id)))

        on-move
        (fn [team-id]
          (let [data  {:id (:id project) :team-id team-id}
                mdata {:on-success #(on-move-success team-id)}]
            #(st/emit! (dm/success (tr "dashboard.success-move-project"))
                      (dd/move-project (with-meta data mdata)))))

        delete-fn
        (fn [_]
          (st/emit! (dm/success (tr "dashboard.success-delete-project"))
                    (dd/delete-project project)
                    (dd/go-to-projects (:team-id project))))

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
           (when (fn? on-import) (on-import))))]

    [:*
     [:& udi/import-form {:ref file-input
                          :project-id (:id project)
                          :on-finish-import on-finish-import}]
     [:& context-menu
      {:on-close on-menu-close
       :show show?
       :fixed? (or (not= top 0) (not= left 0))
       :min-width? true
       :top top
       :left left
       :options [(when-not (:is-default project)
                   [(tr "labels.rename") on-edit nil "project-rename"])
                 (when-not (:is-default project)
                   [(tr "dashboard.duplicate") on-duplicate nil "project-duplicate"])
                 (when-not (:is-default project)
                   [(tr "dashboard.pin-unpin") toggle-pin])
                 (when (and (seq teams) (not (:is-default project)))
                   [(tr "dashboard.move-to") nil
                    (for [team teams]
                      [(:name team) (on-move (:id team))])
                    "project-move-to"])
                 (when (some? on-import)
                   [(tr "dashboard.import") on-import-files nil "file-import"])
                 (when-not (:is-default project)
                   [:separator])
                 (when-not (:is-default project)
                   [(tr "labels.delete") on-delete nil "project-delete"])]}]]))

