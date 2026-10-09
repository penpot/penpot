;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.delete-object-test
  (:require
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- submitted-events
  [audit-mock]
  (->> (:call-args-list @audit-mock)
       (map second)))

(defn- cascade-events
  [audit-mock]
  (filter #(= "cascade-deleted" (:name %))
          (submitted-events audit-mock)))

(t/deftest delete-object-team-cascade-emits-event
  ;; The cascade marks the team, its projects and their files; the event
  ;; carries every affected id so a projection can follow without reading
  ;; production tables.
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (with-redefs [cf/flags (conj cf/flags :audit-log)]
      (let [profile    (th/create-profile* 1 {:is-active true})
            team       (th/create-team* 91 {:profile-id (:id profile)})
            project    (th/create-project* 91 {:profile-id (:id profile)
                                               :team-id (:id team)})
            file       (th/create-file* 91 {:profile-id (:id profile)
                                            :project-id (:id project)})
            deleted-at (ct/now)]
        (th/run-task! :delete-object {:object :team
                                      :id (:id team)
                                      :deleted-at deleted-at})
        (t/is (some? (:deleted-at (th/db-get :team {:id (:id team)}
                                             {::db/remove-deleted false}))))
        (t/is (some? (:deleted-at (th/db-get :project {:id (:id project)}
                                             {::db/remove-deleted false}))))
        (t/is (some? (:deleted-at (th/db-get :file {:id (:id file)}
                                             {::db/remove-deleted false}))))
        (let [events (cascade-events audit-mock)]
          (t/is (= 1 (count events)))
          (let [props (:props (first events))]
            (t/is (= "team" (name (:object props))))
            (t/is (= (:id team) (:id props)))
            (t/is (= deleted-at (:deleted-at props)))
            (t/is (= #{(:id team)} (set (:team-ids props))))
            (t/is (= #{(:default-project-id team) (:id project)}
                     (set (:project-ids props))))
            (t/is (= #{(:id file)} (set (:file-ids props))))))))))

(t/deftest delete-object-file-cascade-emits-event
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (with-redefs [cf/flags (conj cf/flags :audit-log)]
      (let [profile    (th/create-profile* 1 {:is-active true})
            team       (th/create-team* 92 {:profile-id (:id profile)})
            project    (th/create-project* 92 {:profile-id (:id profile)
                                               :team-id (:id team)})
            file       (th/create-file* 92 {:profile-id (:id profile)
                                            :project-id (:id project)})
            deleted-at (ct/now)]
        (th/run-task! :delete-object {:object :file
                                      :id (:id file)
                                      :deleted-at deleted-at})
        (let [events (cascade-events audit-mock)]
          (t/is (= 1 (count events)))
          (let [props (:props (first events))]
            (t/is (= "file" (name (:object props))))
            (t/is (= (:id file) (:id props)))
            (t/is (= [] (:team-ids props)))
            (t/is (= [] (:project-ids props)))
            (t/is (= [(:id file)] (:file-ids props)))))))))

(t/deftest delete-object-missing-row-emits-nothing
  ;; A cascade over an unknown id marks nothing, so there is nothing to report.
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (with-redefs [cf/flags (conj cf/flags :audit-log)]
      (th/run-task! :delete-object {:object :team
                                    :id (uuid/next)
                                    :deleted-at (ct/now)})
      (t/is (empty? (:call-args-list @audit-mock))))))

(t/deftest delete-object-snapshot-emits-nothing
  ;; Snapshots have no projection table; their cascade stays silent.
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (with-redefs [cf/flags (conj cf/flags :audit-log)]
      (th/run-task! :delete-object {:object :snapshot
                                    :id (uuid/next)
                                    :file-id (uuid/next)
                                    :deleted-at (ct/now)})
      (t/is (empty? (:call-args-list @audit-mock))))))
