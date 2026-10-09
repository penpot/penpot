;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.objects-gc-test
  (:require
   [app.config :as cf]
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

(defn- events-named
  [audit-mock event-name]
  (filter #(= event-name (:name %))
          (submitted-events audit-mock)))

(t/deftest gc-emits-hard-delete-team-event
  ;; When the GC physically deletes a team, the projection must drop its row,
  ;; so the event carries the purged ids.
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (with-redefs [cf/flags (conj cf/flags :audit-log)]
      (let [profile (th/create-profile* 1 {:is-active true})
            team    (th/create-team* 71 {:profile-id (:id profile)})]
        (th/command! {::th/type :delete-team
                      ::rpc/profile-id (:id profile)
                      :id (:id team)})
        ;; Run the queued cascade so the default project is marked deleted too,
        ;; then purge everything the way production does.
        (th/run-pending-tasks!)
        (th/reset-mock! audit-mock)
        (th/run-task! :objects-gc {:skip-delay true})
        (t/is (nil? (th/db-get :team {:id (:id team)})))
        (let [events (events-named audit-mock "hard-delete-team")]
          (t/is (= 1 (count events)))
          (let [event (first events)]
            (t/is (= "command" (:type event)))
            (t/is (= [(:id team)] (get-in event [:props :ids])))
            (t/is (= "objects-gc" (get-in event [:context :triggered-by])))))))))

(t/deftest gc-emits-hard-delete-project-event
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (with-redefs [cf/flags (conj cf/flags :audit-log)]
      (let [profile (th/create-profile* 1 {:is-active true})
            team    (th/create-team* 72 {:profile-id (:id profile)})
            project (th/create-project* 72 {:profile-id (:id profile)
                                            :team-id (:id team)})]
        (th/command! {::th/type :delete-project
                      ::rpc/profile-id (:id profile)
                      :id (:id project)})
        (th/run-pending-tasks!)
        (th/reset-mock! audit-mock)
        (th/run-task! :objects-gc {:skip-delay true})
        (t/is (nil? (th/db-get :project {:id (:id project)})))
        (let [events (events-named audit-mock "hard-delete-project")]
          (t/is (= 1 (count events)))
          (let [event (first events)]
            (t/is (= "command" (:type event)))
            (t/is (= [(:id project)] (get-in event [:props :ids])))
            (t/is (= "objects-gc" (get-in event [:context :triggered-by])))))))))

(t/deftest gc-emits-hard-delete-file-event
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (with-redefs [cf/flags (conj cf/flags :audit-log)]
      (let [profile (th/create-profile* 1 {:is-active true})
            team    (th/create-team* 73 {:profile-id (:id profile)})
            project (th/create-project* 73 {:profile-id (:id profile)
                                            :team-id (:id team)})
            file    (th/create-file* 73 {:profile-id (:id profile)
                                         :project-id (:id project)})]
        (th/command! {::th/type :delete-file
                      ::rpc/profile-id (:id profile)
                      :id (:id file)})
        (th/run-pending-tasks!)
        (th/reset-mock! audit-mock)
        (th/run-task! :objects-gc {:skip-delay true})
        (t/is (nil? (th/db-get :file {:id (:id file)})))
        (let [events (events-named audit-mock "hard-delete-file")]
          (t/is (= 1 (count events)))
          (let [event (first events)]
            (t/is (= "command" (:type event)))
            (t/is (= [(:id file)] (get-in event [:props :ids])))
            (t/is (= "objects-gc" (get-in event [:context :triggered-by])))))))))

(t/deftest gc-emits-no-event-without-audit-log-flag
  ;; The GC must stay silent when the audit log is off, even though the row is
  ;; still purged.
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (let [profile (th/create-profile* 1 {:is-active true})
          team    (th/create-team* 74 {:profile-id (:id profile)})]
      (th/command! {::th/type :delete-team
                    ::rpc/profile-id (:id profile)
                    :id (:id team)})
      (th/run-pending-tasks!)
      (th/reset-mock! audit-mock)
      (th/run-task! :objects-gc {:skip-delay true})
      (t/is (nil? (th/db-get :team {:id (:id team)})))
      (t/is (empty? (:call-args-list @audit-mock))))))
