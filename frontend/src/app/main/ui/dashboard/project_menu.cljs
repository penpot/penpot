;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.main.ui.dashboard.project-menu
  (:require
   [app.main.data.dashboard :as dd]
   [app.main.data.messages :as dm]
   [app.main.data.modal :as modal]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.main.ui.components.context-menu :refer [context-menu]]
   [app.main.ui.context :as ctx]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.router :as rt]
   [beicon.core :as rx]
   [rumext.alpha :as mf]))

(mf/defc project-menu
  [{:keys [project show? on-edit on-menu-close top left] :as props}]
  (assert (some? project) "missing `project` prop")
  (assert (boolean? show?) "missing `show?` prop")
  (assert (fn? on-edit) "missing `on-edit` prop")
  (assert (fn? on-menu-close) "missing `on-menu-close` prop")
  (let [top  (or top 0)
        left (or left 0)

        current-team-id (mf/use-ctx ctx/current-team-id)
        teams           (mf/use-state nil)

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
        (st/emitf (dd/toggle-project-pin project))

        on-move-success
        (fn [team-id]
          (st/emit! (dd/go-to-projects team-id)))

        on-move
        (fn [team-id]
          (let [data  {:id (:id project) :team-id team-id}
                mdata {:on-success #(on-move-success team-id)}]
            (st/emitf (dm/success (tr "dashboard.success-move-project"))
                      (dd/move-project (with-meta data mdata)))))

        delete-fn
        (fn [event]
          (st/emit! (dm/success (tr "dashboard.success-delete-project"))
                    (dd/delete-project project)
                    (dd/go-to-projects (:team-id project))))

        on-delete
        (st/emitf
         (modal/show
          {:type :confirm
           :title (tr "modals.delete-project-confirm.title")
           :message (tr "modals.delete-project-confirm.message")
           :accept-label (tr "modals.delete-project-confirm.accept")
           :on-accept delete-fn}))]

    (mf/use-effect
     (fn []
       (->> (rp/query! :teams)
            (rx/map (fn [teams]
                      (into [] (remove #(= (:id %) current-team-id)) teams)))
            (rx/subs #(reset! teams %)))))

    (when @teams
      [:& context-menu {:on-close on-menu-close
                        :show show?
                        :fixed? (or (not= top 0) (not= left 0))
                        :min-width? true
                        :top top
                        :left left
                        :options [[(tr "labels.rename") on-edit]
                                  [(tr "dashboard.duplicate") on-duplicate]
                                  [(tr "dashboard.pin-unpin") toggle-pin]
                                  (when (seq @teams)
                                    [(tr "dashboard.move-to") nil
                                     (for [team @teams]
                                       [(:name team) (on-move (:id team))])])
                                  [:separator]
                                  [(tr "labels.delete") on-delete]]}])))

