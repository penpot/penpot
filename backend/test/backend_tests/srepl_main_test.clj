;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.srepl-main-test
  (:require
   [app.common.time :as ct]
   [app.db :as db]
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

(t/deftest cascade-counts-only-the-submits-that-happen
  ;; `::jobs/dedupe` returns nil when a not-started job already covers the
  ;; row, so a second pass finds it but enqueues nothing.
  (let [profile    (create-deleted-profile! 998 (ct/in-past {:days 10}))
        first-run  (#'srepl/submit-deleted-cascade
                    th/*system* {:table "profile" :object :profile})
        second-run (#'srepl/submit-deleted-cascade
                    th/*system* {:table "profile" :object :profile})
        dry-run    (#'srepl/submit-deleted-cascade
                    th/*system* {:table "profile" :object :profile :dry-run? true})]
    (t/is (= 1 (:found first-run)))
    (t/is (= 1 (:submitted first-run)))
    (t/testing "the second pass finds the row but dedupe drops the submit"
      (t/is (= 1 (:found second-run)))
      (t/is (= 0 (:submitted second-run))))
    (t/testing "dry-run still counts without enqueueing"
      (t/is (= 1 (:found dry-run)))
      (t/is (= 0 (:submitted dry-run))))
    (t/is (= 1 (count (th/db-query :job {:label (str (:id profile))}))))))

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

(t/deftest delete-file-carries-its-team
  ;; The SREPL event must carry its team, so a consumer can key on it
  ;; without joining production tables.
  (with-mocks [audit-mock {:target 'app.loggers.audit/insert :return nil}
               wrk-mock {:target 'app.jobs/invoke :return nil}]
    (let [profile (th/create-profile* 1 {:is-active true})
          team-id (:default-team-id profile)
          proj    (th/create-project* 1 {:profile-id (:id profile) :team-id team-id})
          file    (th/create-file* 1 {:profile-id (:id profile) :project-id (:id proj)})]
      (with-redefs [sys/system th/*system*]
        (srepl/delete-file! (str (:id file))))
      (let [event (-> @audit-mock :call-args-list first second)]
        (t/is (= "delete-file" (:name event)))
        (t/is (= team-id (get-in event [:props :team-id])))))))

(t/deftest restore-file-carries-its-team
  (with-mocks [audit-mock {:target 'app.loggers.audit/insert :return nil}]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj    (th/create-project* 1 {:profile-id (:id profile)
                                         :team-id (:default-team-id profile)})
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:id proj)})]
      (with-redefs [sys/system th/*system*]
        (srepl/restore-file! (str (:id file))))
      (let [event (-> @audit-mock :call-args-list first second)]
        (t/is (= "restore-file" (:name event)))
        (t/is (= (:default-team-id profile) (get-in event [:props :team-id])))))))

(t/deftest restore-team-carries-its-team
  (with-mocks [audit-mock {:target 'app.loggers.audit/insert :return nil}]
    (let [profile (th/create-profile* 1 {:is-active true})
          team    (th/create-team* 3 {:profile-id (:id profile)})]
      (with-redefs [sys/system th/*system*]
        (srepl/restore-team! (str (:id team))))
      (let [event (-> @audit-mock :call-args-list first second)]
        (t/is (= "restore-team" (:name event)))
        (t/is (= (:id team) (get-in event [:props :team-id])))))))

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

(t/deftest restore-profile-restores-only-the-teams-the-cascade-took
  ;; The profile cascade stamps its live owned teams with the profile's own
  ;; instant; restoring the profile brings back exactly those, not a team the
  ;; owner had already deleted on its own.
  (with-mocks [audit-mock {:target 'app.loggers.audit/insert :return nil}]
    (let [profile   (th/create-profile* 1 {:is-active true})
          owned     (:default-team-id profile)
          unrelated (:id (th/create-team* 2 {:profile-id (:id profile)}))]
      ;; A team owned by the profile, deleted on its own long before.
      (th/db-exec! ["UPDATE team SET deleted_at = ? WHERE id = ?"
                    (ct/in-past {:days 30}) unrelated])
      ;; The real cascade marks the profile and its live owned teams.
      (th/run-task! :delete-object {:object :profile
                                    :id (:id profile)
                                    :deleted-at (ct/now)})
      (t/is (some? (:deleted-at (th/db-get :profile {:id (:id profile)}
                                           {::db/remove-deleted false}))))
      (t/is (some? (:deleted-at (th/db-get :team {:id owned}
                                           {::db/remove-deleted false}))))
      (with-redefs [sys/system th/*system*]
        (srepl/restore-profile! (str (:id profile))))
      (t/is (nil? (:deleted-at (th/db-get :profile {:id (:id profile)}
                                          {::db/remove-deleted false}))))
      (t/is (nil? (:deleted-at (th/db-get :team {:id owned}
                                          {::db/remove-deleted false}))))
      (t/is (some? (:deleted-at (th/db-get :team {:id unrelated}
                                           {::db/remove-deleted false}))))
      (let [event (-> @audit-mock :call-args-list first second)]
        (t/is (= "restore-profile" (:name event)))
        (t/is (= [owned] (get-in event [:props :team-ids])))))))

(t/deftest update-profile-email-changes-email
  (with-mocks [audit-mock {:target 'app.loggers.audit/insert :return nil}]
    (with-redefs [sys/system th/*system*]
      (let [profile (th/create-profile* 1 {:is-active true})
            result  (srepl/update-profile-email!
                     :email (:email profile)
                     :new-email "corrected@example.com")]
        (t/is (= :updated result))
        (t/is (= "corrected@example.com"
                 (:email (th/db-get :profile {:id (:id profile)}))))
        (let [event (->> (:call-args-list @audit-mock)
                         (map second)
                         first)]
          (t/is (= "update-profile-email" (:name event)))
          (t/is (= "action" (:type event)))
          (t/is (= (:id profile) (get-in event [:props :id])))
          (t/is (= (:email profile) (get-in event [:props :email])))
          (t/is (= "corrected@example.com"
                   (get-in event [:props :new-email]))))))))

(t/deftest update-profile-email-rejects-collision
  (with-redefs [sys/system th/*system*]
    (let [profile1 (th/create-profile* 1 {:is-active true})
          profile2 (th/create-profile* 2 {:is-active true})
          error    (try
                     (srepl/update-profile-email!
                      :email (:email profile1)
                      :new-email (:email profile2))
                     (catch Throwable e e))]
      (t/is (th/ex-of-code? error :email-already-exists))
      (t/is (= (:email profile1)
               (:email (th/db-get :profile {:id (:id profile1)})))))))

(t/deftest update-profile-email-unchanged-when-same
  (with-mocks [audit-mock {:target 'app.loggers.audit/insert :return nil}]
    (with-redefs [sys/system th/*system*]
      (let [profile (th/create-profile* 1 {:is-active true})
            result  (srepl/update-profile-email!
                     :email (:email profile)
                     :new-email (:email profile))]
        (t/is (= :unchanged result))
        (t/is (empty? (:call-args-list @audit-mock)))))))

(t/deftest update-profile-email-not-found
  (with-mocks [audit-mock {:target 'app.loggers.audit/insert :return nil}]
    (with-redefs [sys/system th/*system*]
      (let [error (try
                    (srepl/update-profile-email!
                     :email "missing@example.com"
                     :new-email "corrected@example.com")
                    (catch Throwable e e))]
        (t/is (th/ex-of-code? error :profile-not-found))
        (t/is (= :not-found (:type (ex-data error))))
        (t/is (empty? (:call-args-list @audit-mock)))))))

(t/deftest clear-email-complaint-reports-deletes-and-unmutes
  (with-mocks [audit-mock {:target 'app.loggers.audit/insert :return nil}]
    (with-redefs [sys/system th/*system*]
      (let [profile (th/create-profile* 1 {:is-active true :is-muted true
                                           :email "bounced@example.com"})
            _       (th/create-complaint-for th/*system*
                                             {:id (:id profile) :type :bounce})
            _       (th/create-complaint-for th/*system*
                                             {:id (:id profile) :type :complaint})
            _       (th/create-global-complaint-for th/*system*
                                                    {:email (:email profile)
                                                     :type :bounce})
            _       (th/create-global-complaint-for th/*system*
                                                    {:email (:email profile)
                                                     :type :complaint})
            result  (srepl/clear-email-complaint-reports! (:email profile))]
        (t/is (= :cleared result))
        (t/is (empty? (th/db-query :global-complaint-report
                                   {:email (:email profile)})))
        (t/is (empty? (th/db-query :profile-complaint-report
                                   {:profile-id (:id profile)})))
        (t/is (false? (:is-muted (th/db-get :profile {:id (:id profile)}))))
        (let [event (->> (:call-args-list @audit-mock)
                         (map second)
                         first)]
          (t/is (= "clear-email-complaint-reports" (:name event)))
          (t/is (= "action" (:type event)))
          (t/is (= (:id profile) (get-in event [:props :id])))
          (t/is (= (:email profile) (get-in event [:props :email]))))))))

(t/deftest clear-email-complaint-reports-without-profile
  (with-mocks [audit-mock {:target 'app.loggers.audit/insert :return nil}]
    (with-redefs [sys/system th/*system*]
      (let [email "orphan-bounce@example.com"
            _     (th/create-global-complaint-for th/*system*
                                                  {:email email :type :bounce})
            result (srepl/clear-email-complaint-reports! email)]
        (t/is (= :cleared result))
        (t/is (empty? (th/db-query :global-complaint-report {:email email})))
        (let [event (->> (:call-args-list @audit-mock)
                         (map second)
                         first)]
          (t/is (= "clear-email-complaint-reports" (:name event)))
          (t/is (= email (get-in event [:props :email])))
          (t/is (nil? (get-in event [:props :id]))))))))
