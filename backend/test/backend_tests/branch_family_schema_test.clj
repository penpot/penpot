;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.branch-family-schema-test
  "The family schema of migration 0817: a file and its branches share a
  family, the rows the family owns name it, and no foreign key lets the
  deletion of a file take a review, a thread, or a task with it."
  (:require
   [app.common.geom.point :as gpt]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.email :as eml]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- branching-flags
  "The test flags plus `:branching`, read inside the fixture so the flags
  the fixture turns off stay off."
  []
  (conj cf/flags :branching))

(defn- query
  [sql & params]
  (db/exec! th/*pool* (into [sql] params)))

(defn- hard-delete-file!
  "Hard-delete a file row the way the objects GC does: the rows that
  still name it go first, or the `file` foreign keys reject the delete
  with the rows the GC purges before the file."
  [file-id]
  (th/db-force-delete :file-change {:file-id file-id})
  (th/db-force-delete :file-data {:file-id file-id})
  (th/db-force-delete :file {:id file-id}))

(defn- family-of
  [table id]
  (-> (query (str "SELECT family_id FROM " table " WHERE id = ?") id)
      first
      :family-id))

(defn- page-id-of
  [profile file-id]
  (-> (th/command! {::th/type :get-file
                    ::rpc/profile-id (:id profile)
                    :id file-id})
      :result :data :pages first))

(defn- setup-branch
  "A file, a branch of it, an open review of the branch, a thread and a
  task on the branch, and a thread on the file."
  []
  (let [author   (th/create-profile* 1 {:is-active true})
        reviewer (th/create-profile* 2 {:is-active true})
        file     (th/create-file* 1 {:profile-id (:id author)
                                     :project-id (:default-project-id author)})
        page-id  (page-id-of author (:id file))
        _        (th/create-team-role* {:team-id (:default-team-id author)
                                        :profile-id (:id reviewer)
                                        :role :editor})
        branch   (:result (th/command! {::th/type :create-file-branch
                                        ::rpc/profile-id (:id author)
                                        :file-id (:id file)
                                        :name "feature-login"}))
        review   (:result (th/command! {::th/type :create-branch-review
                                        ::rpc/profile-id (:id author)
                                        :branch-id (:id branch)
                                        :title "Checkout redesign"
                                        :reviewers [(:id reviewer)]}))
        thread   (fn [file-id]
                   (:result (th/command! {::th/type :create-comment-thread
                                          ::rpc/profile-id (:id author)
                                          :file-id file-id
                                          :page-id page-id
                                          :frame-id uuid/zero
                                          :position (gpt/point 0)
                                          :content "Check the spacing"})))
        task     (:result (th/command! {::th/type :create-task
                                        ::rpc/profile-id (:id author)
                                        :file-id (:branch-file-id branch)
                                        :page-id page-id
                                        :frame-id uuid/zero
                                        :position (gpt/point 0)
                                        :title "Use the secondary token"}))]
    {:file file
     :branch branch
     :branch-file-id (:branch-file-id branch)
     :review review
     :file-thread (thread (:id file))
     :branch-thread (thread (:branch-file-id branch))
     :task task}))

(t/deftest a-file-founds-its-own-family
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    (t/is (= (:id file) (family-of "file" (:id file))))
    (t/is (= 1 (count (query "SELECT id FROM file_family WHERE id = ?" (:id file)))))))

(t/deftest the-family-goes-with-its-last-file
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    (hard-delete-file! (:id file))
    (t/is (empty? (query "SELECT id FROM file_family WHERE id = ?" (:id file))))))

(t/deftest a-branch-and-its-rows-carry-the-family-of-the-source
  (with-redefs [cf/flags (branching-flags)
                eml/send-template (fn [_ _] nil)]
    (let [{:keys [file branch-file-id review file-thread branch-thread task]} (setup-branch)
          family-id (:id file)]
      (t/is (some? branch-file-id))
      (t/is (= family-id (family-of "file" (:id file))))
      (t/is (= family-id (family-of "file" branch-file-id)))
      (t/is (= family-id (family-of "file_branch_review" (:id review))))
      (t/is (= family-id (family-of "comment_thread" (:id file-thread))))
      (t/is (= family-id (family-of "comment_thread" (:id branch-thread))))
      (t/is (= family-id (family-of "file_task" (:id task))))

      (t/testing "the threads of the file and of its branch share one numbering"
        (let [seqns (->> (query "SELECT seqn FROM comment_thread WHERE family_id = ?" family-id)
                         (mapv :seqn))
              top   (-> (query "SELECT thread_seqn FROM file_family WHERE id = ?" family-id)
                        first :thread-seqn)]
          ;; the file's thread, the branch's thread, and the task's thread
          (t/is (= 3 (count seqns)))
          (t/is (= (count seqns) (count (set seqns))))
          (t/is (= top (apply max seqns)))))

      (t/testing "hard-deleting the branch file keeps the review, its threads, and its tasks"
        (hard-delete-file! branch-file-id)
        (t/is (= family-id (family-of "file_branch_review" (:id review))))
        (t/is (= [[nil family-id]]
                 (map (juxt :file-id :family-id)
                      (query "SELECT file_id, family_id FROM comment_thread WHERE id = ?"
                             (:id branch-thread)))))
        (t/is (= [[nil family-id]]
                 (map (juxt :file-id :family-id)
                      (query "SELECT file_id, family_id FROM file_task WHERE id = ?"
                             (:id task)))))
        (t/is (seq (query "SELECT id FROM file_family WHERE id = ?" family-id)))))))

(t/deftest no-file-deletion-cascades-into-the-family-rows
  ;; Only the death of the family may delete a review, a thread, or a
  ;; task: a cascade from `file` would destroy them at every branch GC.
  (t/is (= []
           (query "SELECT conrelid::regclass::text AS tbl, conname
                     FROM pg_constraint
                    WHERE contype = 'f'
                      AND confdeltype = 'c'
                      AND confrelid = 'file'::regclass
                      AND conrelid IN ('file_branch_review'::regclass,
                                       'comment_thread'::regclass,
                                       'file_task'::regclass)"))))

(t/deftest thread-numbers-are-unique-per-family
  (t/is (= [{:indisunique true :columns "family_id, seqn"}]
           (query "SELECT i.indisunique,
                          (SELECT string_agg(a.attname, ', ' ORDER BY k.ord)
                             FROM unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)
                             JOIN pg_attribute AS a
                               ON (a.attrelid = i.indrelid AND a.attnum = k.attnum)) AS columns
                     FROM pg_index AS i
                    WHERE i.indexrelid = 'comment_thread__family_id__seqn__idx'::regclass")))
  (t/is (empty? (query "SELECT attname
                          FROM pg_attribute
                         WHERE attrelid = 'file'::regclass
                           AND attname = 'comment_thread_seqn'
                           AND NOT attisdropped"))))
