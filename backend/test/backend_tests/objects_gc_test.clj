;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.objects-gc-test
  (:require
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [app.tasks.objects-gc :as ogc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- create-deleted-file!
  "Creates a file and applies the deletion cascade on it (the same task the RPC
  uses), so its children are marked as deleted for the given date. Returns the
  file."
  [profile i deleted-at]
  (let [file (th/create-file* i {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)})]
    (th/run-task! :delete-object {:object :file
                                  :deleted-at deleted-at
                                  :id (:id file)})
    file))

(defn- block-file!
  "Leaves an unmarked `file_media_object` pointing at the file, which is what
  happens in production: a child that the deletion cascade did not mark. Its
  foreign key towards `file` is `NO ACTION`, so it blocks the file deletion."
  [file]
  (let [media (sto/put-object! (::sto/storage th/*system*)
                               {::sto/content (sto/content "media")
                                :content-type "text/plain"
                                :bucket "file-media-object"})]
    (th/create-file-media-object* {:file-id (:id file)
                                   :media-id (:id media)})))

(defn- find-file
  [id]
  (th/db-get :file {:id id} {::db/remove-deleted false}))

(defn- chunk-error
  "The first captured log record that reports a skipped chunk for `obj`,
  waiting for it because logging is asynchronous."
  [records obj]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (let [record (first (filter (fn [record]
                                    (let [props (::l/props record)]
                                      (and (= obj (get props :obj))
                                           (= "error on chunk deletion, skipping it"
                                              (get props :hint)))))
                                  @records))]
        (cond
          record record
          (> (System/currentTimeMillis) deadline) nil
          :else (do (Thread/sleep 10) (recur)))))))

(t/deftest deletes-the-pending-files
  (let [profile (th/create-profile* 1)
        file    (create-deleted-file! profile 1 (ct/in-past "10m"))
        result  (th/run-task! :objects-gc {})]

    (t/is (pos? (:processed result)))
    (t/is (nil? (find-file (:id file))))))

(t/deftest profile-cascade-keeps-profile-deletion-date
  ;; The cascade marks the owned rows with the deletion date of the profile,
  ;; like the RPC delete-profile flow does, not with the timestamp of the run.
  ;; The team survives because only the profiles chunk runs here, so its row
  ;; still shows that date.
  (let [profile (th/create-profile* 1)
        _       (th/db-update! :profile {:deleted-at (ct/in-past "30m")} {:id (:id profile)})

        deleted-at (:deleted-at (th/db-get :profile {:id (:id profile)}
                                           {::db/remove-deleted false}))

        cfg     {::db/pool th/*pool*
                 ::sto/storage (::sto/storage th/*system*)
                 ::ogc/threshold deleted-at}
        total   (#'ogc/delete-profiles-chunk cfg [(:id profile)])]

    (t/testing "the profile is gone and the chunk reports it"
      (t/is (= 1 total))
      (t/is (nil? (th/db-get :profile {:id (:id profile)} {::db/remove-deleted false}))))

    (t/testing "the owned team keeps the deletion date of the profile"
      (t/is (= deleted-at (:deleted-at (th/db-get :team {:id (:default-team-id profile)}
                                                  {::db/remove-deleted false})))))))

(t/deftest blocked-chunk-does-not-stop-the-run
  ;; Chunks of one file: the blocked file takes down its own chunk and nothing
  ;; else, so the clean file of the next chunk is still deleted and the run
  ;; finishes. The next run retries the blocked file.
  (let [profile (th/create-profile* 1)
        blocked (create-deleted-file! profile 1 (ct/in-past "30m"))
        clean   (create-deleted-file! profile 2 (ct/in-past "20m"))

        _       (block-file! blocked)

        records (atom [])
        _       (add-watch l/log-record ::capture
                           (fn [_ _ _ record] (swap! records conj record)))
        result  (try
                  (th/run-task! :objects-gc {:chunk-size 1})
                  (finally
                    (remove-watch l/log-record ::capture)))]

    (t/testing "the clean file of the next chunk is deleted"
      (t/is (nil? (find-file (:id clean)))))

    (t/testing "the blocked file is still pending"
      (t/is (some? (find-file (:id blocked)))))

    (t/testing "the failed chunk is reported"
      (t/is (some? (chunk-error records "file"))))

    (t/testing "the run finishes"
      (t/is (pos? (:processed result))))))

(defn- submitted-events
  [audit-mock]
  (->> (:call-args-list @audit-mock)
       (map second)))

(defn- events-named
  [audit-mock event-name]
  (filter #(= event-name (:name %))
          (submitted-events audit-mock)))

(t/deftest gc-emits-hard-delete-objects-for-teams
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
        (th/run-pending-jobs)
        (th/reset-mock! audit-mock)
        (th/run-task! :objects-gc {:skip-delay true})
        (t/is (nil? (th/db-get :team {:id (:id team)} {::db/remove-deleted false})))
        (let [events (filter #(contains? (:props %) :team-ids)
                             (events-named audit-mock "hard-delete-objects"))]
          (t/is (= 1 (count events)))
          (let [event (first events)]
            (t/is (= "command" (:type event)))
            (t/is (= [(:id team)] (get-in event [:props :team-ids])))
            (t/is (= "objects-gc" (get-in event [:context :triggered-by])))))))))

(t/deftest gc-emits-hard-delete-objects-for-projects
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (with-redefs [cf/flags (conj cf/flags :audit-log)]
      (let [profile (th/create-profile* 1 {:is-active true})
            team    (th/create-team* 72 {:profile-id (:id profile)})
            project (th/create-project* 72 {:profile-id (:id profile)
                                            :team-id (:id team)})]
        (th/command! {::th/type :delete-project
                      ::rpc/profile-id (:id profile)
                      :id (:id project)})
        (th/run-pending-jobs)
        (th/reset-mock! audit-mock)
        (th/run-task! :objects-gc {:skip-delay true})
        (t/is (nil? (th/db-get :project {:id (:id project)} {::db/remove-deleted false})))
        (let [events (filter #(contains? (:props %) :project-ids)
                             (events-named audit-mock "hard-delete-objects"))]
          (t/is (= 1 (count events)))
          (let [event (first events)]
            (t/is (= "command" (:type event)))
            (t/is (= [(:id project)] (get-in event [:props :project-ids])))
            (t/is (= "objects-gc" (get-in event [:context :triggered-by])))))))))

(t/deftest gc-emits-hard-delete-objects-for-files
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
        (th/run-pending-jobs)
        (th/reset-mock! audit-mock)
        (th/run-task! :objects-gc {:skip-delay true})
        (t/is (nil? (th/db-get :file {:id (:id file)} {::db/remove-deleted false})))
        (let [events (filter #(contains? (:props %) :file-ids)
                             (events-named audit-mock "hard-delete-objects"))]
          (t/is (= 1 (count events)))
          (let [event (first events)]
            (t/is (= "command" (:type event)))
            (t/is (= [(:id file)] (get-in event [:props :file-ids])))
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
      (th/run-pending-jobs)
      (th/reset-mock! audit-mock)
      (th/run-task! :objects-gc {:skip-delay true})
      (t/is (nil? (th/db-get :team {:id (:id team)} {::db/remove-deleted false})))
      (t/is (empty? (:call-args-list @audit-mock))))))

(t/deftest gc-emits-events-only-for-committed-chunks
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (with-redefs [cf/flags (conj cf/flags :audit-log)]
      (let [profile     (th/create-profile* 1)
            rolled-back (create-deleted-file! profile 1 (ct/in-past "30m"))
            blocked     (create-deleted-file! profile 2 (ct/in-past "20m"))
            clean       (create-deleted-file! profile 3 (ct/in-past "10m"))]
        (block-file! blocked)
        (th/reset-mock! audit-mock)
        (let [result (th/run-task! :objects-gc {:chunk-size 2})
              events (filter #(contains? (:props %) :file-ids)
                             (events-named audit-mock "hard-delete-objects"))]
          (t/testing "the failed chunk keeps both files"
            (t/is (some? (find-file (:id rolled-back))))
            (t/is (some? (find-file (:id blocked)))))
          (t/testing "the next chunk commits and reports only its deleted file"
            (t/is (nil? (find-file (:id clean))))
            (t/is (pos? (:processed result)))
            (t/is (= 1 (count events)))
            (t/is (= [(:id clean)] (get-in (first events) [:props :file-ids])))))))))
