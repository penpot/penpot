;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.srepl-main-test
  (:require
   [app.common.time :as ct]
   [app.jobs :as jobs]
   [app.srepl.main :as srepl]
   [app.system :as sys]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; --- HELPERS

(defn- capture-submissions!
  "Runs `f` with `jobs/submit` replaced by a collector, and returns the option
  maps it was called with, in order."
  [f]
  (let [submitted (atom [])]
    (with-redefs [jobs/submit (fn [_cfg opts]
                                (swap! submitted conj opts)
                                (get-in opts [::jobs/params :id]))]
      (f))
    @submitted))

(defn- params-of
  [submitted]
  (mapv ::jobs/params submitted))

(defn- create-deleted-profile!
  [i deleted-at]
  (let [profile (th/create-profile* i)]
    (th/db-update! :profile {:deleted-at deleted-at} {:id (:id profile)})
    profile))

;; --- TESTS

(t/deftest parse-emails
  (t/is (= ["some@example.com"]
           (srepl/parse-emails "some@example.com")))

  (t/is (= ["some@example.com" "other@example.com"]
           (srepl/parse-emails "some@example.com,other@example.com")))

  (t/is (= ["some@example.com" "other@example.com"]
           (srepl/parse-emails " some@example.com , other@example.com ,")))

  (t/is (= ["some@example.com" "other@example.com"]
           (srepl/parse-emails ["some@example.com" "other@example.com"])))

  (t/is (= [] (srepl/parse-emails ",")))

  (t/is (thrown? clojure.lang.ExceptionInfo
                 (srepl/parse-emails 42))))

(t/deftest cascade-enqueues-one-delete-object-job-per-deleted-row
  (let [deleted   (create-deleted-profile! 991 (ct/in-past {:days 30}))
        _         (th/create-profile* 992)
        submitted (capture-submissions!
                   #(#'srepl/submit-deleted-cascade
                     th/*system* {:table "profile" :object :profile}))]

    (t/testing "only the soft-deleted row is processed"
      (t/is (= 1 (count submitted))))

    (t/testing "the enqueued job is a delete-object for that row"
      (let [opts (first submitted)]
        (t/is (= :delete-object (::jobs/name opts)))
        (t/is (= {:object :profile :id (:id deleted)}
                 (select-keys (::jobs/params opts) [:object :id])))))

    (t/testing "the date is the row's own, not now"
      (t/is (ct/is-before? (:deleted-at (::jobs/params (first submitted)))
                           (ct/in-past {:days 29}))))

    (t/testing "label is the row id and dedupe is on"
      (t/is (= (str (:id deleted)) (::jobs/label (first submitted))))
      (t/is (true? (::jobs/dedupe (first submitted)))))))

(t/deftest cascade-honours-limit-and-ids
  (let [p1 (create-deleted-profile! 993 (ct/in-past {:days 10}))
        _  (create-deleted-profile! 994 (ct/in-past {:days 9}))
        _  (create-deleted-profile! 995 (ct/in-past {:days 8}))]

    (t/testing "limit bounds how many rows are read"
      (let [submitted (capture-submissions!
                       #(#'srepl/submit-deleted-cascade
                         th/*system* {:table "profile" :object :profile :limit 2}))]
        (t/is (= 2 (count submitted)))))

    (t/testing "ids restricts the run to the given rows"
      (let [submitted (capture-submissions!
                       #(#'srepl/submit-deleted-cascade
                         th/*system* {:table "profile" :object :profile :ids [(:id p1)]}))]
        (t/is (= [{:object :profile :id (:id p1)}]
                 (mapv #(select-keys % [:object :id]) (params-of submitted))))))))

(t/deftest cascade-dry-run-counts-without-enqueueing
  (let [_         (create-deleted-profile! 996 (ct/in-past {:days 10}))
        submitted (atom [])
        result    (with-redefs [jobs/submit (fn [_cfg opts] (swap! submitted conj opts))]
                    (#'srepl/submit-deleted-cascade
                     th/*system* {:table "profile" :object :profile :dry-run? true}))]

    (t/is (= 1 (:found result)))
    (t/is (= 0 (:submitted result)))
    (t/is (empty? @submitted))))

(t/deftest public-helpers-read-their-own-table
  (let [profile (th/create-profile* 997)
        team    (th/create-team* 997 {:profile-id (:id profile)})
        project (th/create-project* 997 {:profile-id (:id profile)
                                         :team-id (:id team)})
        file    (th/create-file* 997 {:profile-id (:id profile)
                                      :project-id (:id project)})
        old     (ct/in-past {:days 10})]

    (th/db-update! :profile {:deleted-at old} {:id (:id profile)})
    (th/db-update! :team {:deleted-at old} {:id (:id team)})
    (th/db-update! :project {:deleted-at old} {:id (:id project)})
    (th/db-update! :file {:deleted-at old} {:id (:id file)})

    (with-redefs [sys/system th/*system*]
      (let [ids (fn [submitted] (into #{} (map :id (params-of submitted))))]

        (t/testing "profiles helper"
          (let [submitted (capture-submissions! #(srepl/process-deleted-profiles-cascade))]
            (t/is (contains? (ids submitted) (:id profile)))))

        (t/testing "teams helper"
          (let [submitted (capture-submissions! #(srepl/process-deleted-teams-cascade))]
            (t/is (contains? (ids submitted) (:id team)))))

        (t/testing "projects helper"
          (let [submitted (capture-submissions! #(srepl/process-deleted-projects-cascade))]
            (t/is (contains? (ids submitted) (:id project)))))

        (t/testing "files helper"
          (let [submitted (capture-submissions! #(srepl/process-deleted-files-cascade))]
            (t/is (contains? (ids submitted) (:id file)))))

        (t/testing "the helpers accept options"
          (let [submitted (capture-submissions!
                           #(srepl/process-deleted-files-cascade :ids [(:id file)]))]
            (t/is (= [{:object :file :id (:id file)}]
                     (mapv #(select-keys % [:object :id]) (params-of submitted))))))))))

(t/deftest delete-profile-emits-identifiable-audit-event
  ;; SREPL has no actor, so the event profile stays zero; the subject id in
  ;; props is what makes the row attributable. Both insert and cascade are
  ;; mocked, so this has no side effects.
  (with-mocks [audit-mock {:target 'app.loggers.audit/insert :return nil}
               wrk-mock {:target 'app.jobs/invoke :return nil}]
    (let [profile (th/create-profile* 1 {:is-active true})]
      (srepl/delete-profile! (str (:id profile)))
      (let [events (->> (:call-args-list @audit-mock)
                        (map second))]
        (t/is (= 1 (count events)))
        (let [event (first events)]
          (t/is (= "delete-profile" (:name event)))
          (t/is (= "action" (:type event)))
          ;; No actor on SREPL calls; insert defaults the missing profile
          ;; to the zero one.
          (t/is (nil? (:profile-id event)))
          (t/is (= (:id profile) (get-in event [:props :id]))))))))

(t/deftest delete-team-emits-identifiable-audit-event
  (with-mocks [audit-mock {:target 'app.loggers.audit/insert :return nil}
               wrk-mock {:target 'app.jobs/invoke :return nil}]
    (let [profile (th/create-profile* 1 {:is-active true})
          team    (th/create-team* 95 {:profile-id (:id profile)})]
      (srepl/delete-team! (str (:id team)))
      (let [events (->> (:call-args-list @audit-mock)
                        (map second))]
        (t/is (= 1 (count events)))
        (let [event (first events)]
          (t/is (= "delete-team" (:name event)))
          (t/is (= "action" (:type event)))
          (t/is (= (:id team) (get-in event [:props :id])))
          (t/is (= "explicit call to delete-team!"
                   (get-in event [:context :cause]))))))))

(t/deftest restore-profile-emits-identifiable-audit-event
  ;; SREPL has no actor, so the event profile stays zero; the subject id in
  ;; props is what makes the row attributable.
  (with-mocks [audit-mock {:target 'app.loggers.audit/insert :return nil}
               wrk-mock {:target 'app.jobs/invoke :return nil}]
    (with-redefs [sys/system th/*system*]
      (let [profile (th/create-profile* 1 {:is-active true})]
        (srepl/restore-profile! (str (:id profile)))
        (let [events (->> (:call-args-list @audit-mock)
                          (map second))]
          (t/is (= 1 (count events)))
          (let [event (first events)]
            (t/is (= "restore-profile" (:name event)))
            (t/is (= "action" (:type event)))
            ;; No actor on SREPL calls; insert defaults the missing profile
            ;; to the zero one.
            (t/is (nil? (:profile-id event)))
            (t/is (= (:id profile) (get-in event [:props :id])))))))))
