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
   [app.jobs :as jobs]
   [app.rpc :as-alias rpc]
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
  (filter #(= "delete-object-with-cascade" (:name %))
          (submitted-events audit-mock)))

(t/deftest delete-object-team-cascade-emits-event
  ;; The cascade marks the team, its projects and their files; the event
  ;; carries every affected id so a projection can follow without reading
  ;; production tables.
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (th/with-audit-config (conj cf/flags :nexus)
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
          (t/is (= "action" (:type (first events))))
          (let [props (:props (first events))]
            (t/is (= "team" (name (:object props))))
            (t/is (= (:id team) (:id props)))
            (t/is (= deleted-at (:deleted-at props)))
            ;; The shape is the contract: only the team ids go flat, the
            ;; rest stays under `:cascade`.
            (t/is (= #{:object :id :deleted-at :team-ids :cascade}
                     (set (keys props))))
            ;; Flat team ids for team-keyed consumers, next to the
            ;; per-entity cascade breakdown.
            (t/is (= #{(:id team)} (set (:team-ids props))))
            (t/is (= #{(:id team)} (set (get-in props [:cascade :team-ids]))))
            (t/is (= #{(:default-project-id team) (:id project)}
                     (set (get-in props [:cascade :project-ids]))))
            (t/is (= #{(:id file)} (set (get-in props [:cascade :file-ids]))))))))))

(t/deftest delete-object-file-cascade-emits-event
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (th/with-audit-config (conj cf/flags :nexus)
      (let [profile    (th/create-profile* 1 {:is-active true})
            team       (th/create-team* 92 {:profile-id (:id profile)})
            project    (th/create-project* 92 {:profile-id (:id profile)
                                               :team-id (:id team)})
            file       (th/create-file* 92 {:profile-id (:id profile)
                                            :project-id (:id project)})]
        (t/is (th/success? (th/command! {::th/type :delete-file
                                         ::rpc/profile-id (:id profile)
                                         :id (:id file)})))
        (th/run-pending-jobs)
        (let [events (cascade-events audit-mock)]
          (t/is (= 1 (count events)))
          (t/is (= "action" (:type (first events))))
          (let [props (:props (first events))]
            (t/is (= "file" (name (:object props))))
            (t/is (= (:id file) (:id props)))
            (t/is (= [] (get-in props [:cascade :team-ids])))
            (t/is (= [] (get-in props [:cascade :project-ids])))
            (t/is (= [(:id file)] (get-in props [:cascade :file-ids])))
            (t/is (= #{(:id team)} (set (:team-ids props))))))))))

(defn- get-deadline
  [table where]
  (:deleted-at (th/db-get table where {::db/remove-deleted false})))

(t/deftest queued-file-deletion-matches-a-legacy-timestamp
  (let [profile   (th/create-profile* 1 {:is-active true})
        file-id   (:id (th/create-file* 1 {:profile-id (:id profile)
                                           :project-id (:default-project-id profile)}))
        requested (ct/inst "2025-10-31T00:00:00.123456789Z")
        main      {:file-id file-id :id file-id}]
    ;; The old purge RPC queued the original timestamp rather than the
    ;; value stored by PostgreSQL, which has microsecond precision.
    (th/db-update! :file {:deleted-at requested} {:id file-id})
    (jobs/submit th/*system* {::jobs/name :delete-object
                              ::jobs/params {:object :file :id file-id :deleted-at requested}})
    (let [deadline (get-deadline :file {:id file-id})]
      (t/is (not= requested deadline))
      (th/run-pending-jobs)
      (t/is (= deadline (get-deadline :file-data main))))))

(t/deftest project-cascade-dates-retained-revisions-with-the-earliest-deadline
  (let [profile  (th/create-profile* 1 {:is-active true})
        project  (th/create-project* 1 {:profile-id (:id profile)
                                        :team-id (:default-team-id profile)})
        file-id  (:id (th/create-file* 1 {:profile-id (:id profile)
                                          :project-id (:id project)}))
        retained {:file-id file-id :id (uuid/next)}
        old      {:file-id file-id :id (uuid/next)}
        snapshot {:file-id file-id :id (uuid/next)}
        now      (ct/inst "2025-10-31T00:00:00Z")
        earlier  (ct/plus now (ct/duration {:days 1}))
        old-date (ct/plus now (ct/duration {:hours 1}))
        snap-date (ct/plus now (ct/duration {:days 2}))]
    (th/db-insert! :file-change (assoc retained :kind 2 :retained true :updated-at now))
    (th/db-insert! :file-data (assoc retained :type "revision" :metadata (db/json {:retained true})))
    (th/db-insert! :file-change (assoc old :kind 2 :deleted-at old-date))
    (th/db-insert! :file-data (assoc old :type "revision" :deleted-at old-date))
    (th/db-insert! :file-change (assoc snapshot :kind 1 :label "System" :deleted-at snap-date))
    (th/db-insert! :file-data (assoc snapshot :type "snapshot" :deleted-at snap-date))
    (th/db-update! :file {:deleted-at earlier} {:id file-id})
    (binding [ct/*clock* (ct/fixed-clock now)]
      (t/is (th/success? (th/command! {::th/type :delete-project
                                       ::rpc/profile-id (:id profile)
                                       :id (:id project)})))
      (t/is (ct/is-after? (get-deadline :project {:id (:id project)}) earlier))
      (t/is (nil? (get-deadline :file-change retained)))
      (t/is (nil? (get-deadline :file-data retained))))
    (let [job-time (ct/plus now (ct/duration {:minutes 1}))]
      (binding [ct/*clock* (ct/fixed-clock job-time)]
        (th/run-pending-jobs))
      (t/is (= earlier (get-deadline :file {:id file-id})))
      (t/is (= earlier (get-deadline :file-change retained)))
      (t/is (= earlier (get-deadline :file-data retained)))
      (t/is (= job-time (:updated-at (th/db-get :file-change retained {::db/remove-deleted false}))))
      (t/is (= old-date (get-deadline :file-change old)))
      (t/is (= old-date (get-deadline :file-data old)))
      (t/is (= snap-date (get-deadline :file-change snapshot)))
      (t/is (= snap-date (get-deadline :file-data snapshot))))
    (binding [ct/*clock* (ct/fixed-clock (ct/plus now (ct/duration {:hours 2})))]
      (let [out (th/command! {::th/type :permanently-delete-team-files
                              ::rpc/profile-id (:id profile)
                              :team-id (:default-team-id profile)
                              :ids #{file-id}})]
        (t/is (th/success? out))
        (th/consume-sse (:result out)))
      (th/run-pending-jobs)
      (t/is (= (ct/now) (get-deadline :file-change snapshot)))
      (t/is (= (ct/now) (get-deadline :file-data snapshot))))))

(t/deftest delete-object-missing-row-emits-nothing
  ;; A cascade over an unknown id marks nothing, so there is nothing to report.
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (th/with-audit-config (conj cf/flags :nexus)
      (th/run-task! :delete-object {:object :team
                                    :id (uuid/next)
                                    :deleted-at (ct/now)})
      (t/is (empty? (:call-args-list @audit-mock))))))

(t/deftest delete-object-snapshot-emits-nothing
  ;; Snapshots have no projection table; their cascade stays silent.
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (th/with-audit-config (conj cf/flags :nexus)
      (th/run-task! :delete-object {:object :snapshot
                                    :id (uuid/next)
                                    :file-id (uuid/next)
                                    :deleted-at (ct/now)})
      (t/is (empty? (:call-args-list @audit-mock))))))
