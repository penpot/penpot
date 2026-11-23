;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.objects-gc-test
  (:require
   [app.common.geom.point :as gpt]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.file-changes :as fchanges]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [app.tasks.objects-gc :as ogc]
   [app.util.blob :as blob]
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

(t/deftest deletes-the-pending-file-changes
  ;; The pending file changes are locked per file, with a constant `file_id`,
  ;; so the rows of a file that is pending deletion are gone after the run.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})

        _       (th/db-insert! :file-change
                               {:file-id (:id file)
                                :kind fchanges/revision-kind
                                :deleted-at (ct/in-past "10m")})

        result  (th/run-task! :objects-gc {})]

    (t/is (pos? (:processed result)))
    (t/is (nil? (th/db-get :file-change {:file-id (:id file)}
                           {::db/remove-deleted false})))))

(t/deftest deletes-the-pending-file-changes-of-several-chunks
  ;; More pending rows than the chunk size: the plan walks the cursor and each
  ;; chunk locks its own rows, one query per file, with a constant `file_id`
  ;; so the planner prunes the partition.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        ids     (repeatedly 5 uuid/next)
        _       (doseq [id ids]
                  (th/db-insert! :file-change
                                 {:id id
                                  :file-id (:id file)
                                  :kind fchanges/revision-kind
                                  :deleted-at (ct/in-past "10m")}))
        result  (th/run-task! :objects-gc {:chunk-size 2})]

    (t/is (pos? (:processed result)))
    (t/is (empty? (th/db-query :file-change {:file-id (:id file)}
                               {::db/remove-deleted false})))))

(t/deftest a-chunk-spanning-several-files-deletes-all-of-them
  ;; The rows of one chunk can belong to different files. Each group is locked
  ;; with its own `file_id`, and every row is still deleted; buffering the
  ;; groups instead of locking them would leave them behind.
  (let [profile (th/create-profile* 1)
        opts    {:profile-id (:id profile)
                 :project-id (:default-project-id profile)}
        files   [(th/create-file* 1 opts)
                 (th/create-file* 2 opts)]

        _       (doseq [file files]
                  (dotimes [_ 2]
                    (th/db-insert! :file-change
                                   {:file-id (:id file)
                                    :kind fchanges/revision-kind
                                    :deleted-at (ct/in-past "10m")})))

        ;; one chunk holds the rows of both files
        result  (th/run-task! :objects-gc {:chunk-size 4})]

    (t/is (pos? (:processed result)))
    (doseq [file files]
      (t/is (empty? (th/db-query :file-change {:file-id (:id file)}
                                 {::db/remove-deleted false}))))))

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

(defn- create-review!
  "An open review of a branch of `file`: the branch file, the branch edge
  the review hangs from, and the review row its activity points at. The
  branch joins the family of its source, as `create-file-branch` does."
  [profile file]
  (let [family (:family-id (th/db-get :file {:id (:id file)}))
        branch (th/create-file* 2 {:profile-id (:id profile)
                                   :project-id (:default-project-id profile)})
        _      (th/db-update! :file {:is-branch true
                                     :family-id family}
                              {:id (:id branch)})
        br     (th/db-insert! :file-branch
                              {:id (uuid/next)
                               :branch-file-id (:id branch)
                               :source-file-id (:id file)
                               :base-source-revn 0
                               :created-by (:id profile)
                               :name "feature-checkout"})]
    (th/db-insert! :file-branch-review
                   {:id (uuid/next)
                    :file-branch-id (:id br)
                    :family-id family
                    :title "Checkout redesign"
                    :created-by (:id profile)})))

(defn- insert-activity!
  [profile review revn entries created-at]
  (th/db-insert! :file-branch-review-activity
                 {:id (uuid/next)
                  :review-id (:id review)
                  :profile-id (:id profile)
                  :revn revn
                  :entries (blob/encode entries)
                  :created-at created-at}))

(t/deftest gc-folds-the-activity-rows-of-a-finished-sitting
  ;; The rows of one sitting concatenate into its last row, in order, so
  ;; the reading side replays the same entries; the last revn and the last
  ;; timestamp stay. A sitting whose last row is newer than the gap is
  ;; still open and keeps its rows.
  (let [profile  (th/create-profile* 1)
        file     (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)})
        review   (create-review! profile file)
        shape-id (uuid/next)
        entries  (fn [kind]
                   [{:id shape-id
                     :page-id (:id file)
                     :name "Pay button"
                     :type :rect
                     :kind kind}])
        now      (ct/now)
        _        (insert-activity! profile review 1 (entries :added)
                                   (ct/minus now {:minutes 120}))
        _        (insert-activity! profile review 2 (entries :modified)
                                   (ct/minus now {:minutes 110}))
        row3     (insert-activity! profile review 3 (entries :modified)
                                   (ct/minus now {:minutes 100}))
        row4     (insert-activity! profile review 4 (entries :modified)
                                   (ct/minus now {:minutes 10}))

        result   (th/run-task! :objects-gc {})]

    (t/is (pos? (:processed result)))

    (let [rows (sort-by :created-at
                        (th/db-query :file-branch-review-activity
                                     {:review-id (:id review)}))]
      (t/testing "the sitting folds into its last row and the open one stays"
        (t/is (= 2 (count rows)))
        (t/is (= [(:id row3) (:id row4)] (mapv :id rows))))

      (t/testing "the folded row concatenates the entries in order"
        (t/is (= (into [] cat [(entries :added) (entries :modified) (entries :modified)])
                 (blob/decode (:entries (first rows))))))

      (t/testing "the folded row keeps the last revn and timestamp"
        (t/is (= 3 (:revn (first rows))))
        (t/is (= (:created-at row3) (:created-at (first rows)))))

      (t/testing "the open sitting is untouched"
        (t/is (= 4 (:revn (second rows))))
        (t/is (= (:created-at row4) (:created-at (second rows))))
        (t/is (= (entries :modified) (blob/decode (:entries (second rows)))))))))

(t/deftest a-task-that-names-its-author-does-not-block-the-profile-purge
  ;; A profile that authored a task, was assigned it and moved it is hit by
  ;; three SET NULL actions on the same row during the purge, which also
  ;; deletes the thread and the message the task points at. Postgres
  ;; rechecks the row's keys before those actions clear them; deferring the
  ;; keys to the commit lets every action run first.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [author  (th/create-profile* 1 {:is-active true})
          file    (th/create-file* 1 {:profile-id (:id author)
                                      :project-id (:default-project-id author)})
          page-id (-> (th/command! {::th/type :get-file
                                    ::rpc/profile-id (:id author)
                                    :id (:id file)})
                      :result :data :pages first)
          task    (-> (th/command! {::th/type :create-task
                                    ::rpc/profile-id (:id author)
                                    :file-id (:id file)
                                    :page-id page-id
                                    :frame-id uuid/zero
                                    :position (gpt/point 0)
                                    :title "Use the secondary token"})
                      :result)]

      (t/is (some? (:id task)))

      (th/db-update! :file-task {:assignee-id (:id author)} {:id (:id task)})
      (let [out (th/command! {::th/type :set-task-status
                              ::rpc/profile-id (:id author)
                              :id (:id task)
                              :status "done"})]
        (t/is (nil? (:error out))))
      (th/db-update! :profile {:deleted-at (ct/in-past "30m")} {:id (:id author)})

      (let [deleted-at (:deleted-at (th/db-get :profile {:id (:id author)}
                                               {::db/remove-deleted false}))
            cfg        {::db/pool th/*pool*
                        ::sto/storage (::sto/storage th/*system*)
                        ::ogc/threshold deleted-at}
            total      (#'ogc/delete-profiles-chunk cfg [(:id author)])]

        (t/testing "the chunk purges the profile instead of skipping it"
          (t/is (= 1 total))
          (t/is (nil? (th/db-get :profile {:id (:id author)}
                                 {::db/remove-deleted false}))))

        (t/testing "the task survives with its links and author cleared"
          (let [row (th/db-get :file-task {:id (:id task)}
                               {::db/remove-deleted false})]
            (t/is (some? row))
            (t/is (nil? (:thread-id row)))
            (t/is (nil? (:comment-id row)))
            (t/is (nil? (:created-by row)))
            (t/is (nil? (:assignee-id row)))
            (t/is (nil? (:status-changed-by row)))))))))

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

(t/deftest gc-deletes-file-data-and-touches-its-storage-object
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})
        sobject (sto/put-object! (::sto/storage th/*system*)
                                 {::sto/content (sto/content "file-data")
                                  :content-type "application/octet-stream"
                                  :bucket "file-data"
                                  :file-id (:id file)
                                  :id (:id file)})]

    ;; `main` always carries `id = file_id`: mark the real row as deleted
    ;; instead of inventing a second main row.
    (th/db-update! :file-data
                   {:backend "storage"
                    :data nil
                    :storage-object-id (:id sobject)
                    :deleted-at (ct/in-past "30m")}
                   {:file-id (:id file) :id (:id file)})

    (th/run-task! :objects-gc {})

    (t/testing "the row is purged"
      (t/is (nil? (th/db-get :file-data {:file-id (:id file) :id (:id file)}))))

    (t/testing "the storage object is touched for the storage GC"
      (t/is (some? (:touched-at (th/db-get :storage-object {:id (:id sobject)})))))))

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
