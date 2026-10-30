;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.file-revision-test
  "The payload the `update-file-v2` flow stores for every revision.

  A revision keeps its metadata row in `file_change` and its content in a
  `file_data` row of type `revision` that shares the id and the deletion
  schedule. The payload holds the forward changes of the save and their
  inverse; the column `file_change.changes` is still written for the readers
  deployed today."
  (:require
   [app.common.features :as cfeat]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(defn- with-validation
  [f]
  (with-redefs [cf/flags (conj cf/flags :file-validation :file-schema-validation)]
    (f)))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset with-validation)

(defn- update-file
  [type profile-id file-id params]
  (th/command! (merge {::th/type type
                       ::rpc/profile-id profile-id
                       :id file-id
                       :session-id (uuid/random)
                       :revn 0
                       :vern 0
                       :features cfeat/supported-features}
                      params)))

(defn- commit
  [redo undo]
  {:commit-id (uuid/next) :redo-changes redo :undo-changes undo})

(defn- add-page-commit
  [page-id name]
  (commit [{:type :add-page :id page-id :name name}]
          [{:type :del-page :id page-id}]))

(defn- set-parent
  [page-id shape-id parent-id]
  {:type :mod-obj
   :page-id page-id
   :id shape-id
   :operations [{:type :set :attr :parent-id :val parent-id}]})

(defn- revision-rows
  "The unlabeled rows are revisions, not snapshots. Revisions have a
  future deletion date, so keep deleted rows visible."
  [file-id]
  (->> (th/db-query :file-change {:file-id file-id} {::db/remove-deleted false})
       (filter #(nil? (:label %)))))

(defn- revision-row
  [file-id revn]
  (->> (revision-rows file-id)
       (filter #(= revn (:revn %)))
       (first)))

(defn- payload-row
  [file-id revision-id]
  (th/db-get :file-data
             {:file-id file-id :id revision-id :type "revision"}
             {::db/remove-deleted false}))

(defn- create-file
  []
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)})]
    [profile file]))

(defn- assert-no-revision
  [file-id]
  (t/is (empty? (revision-rows file-id)))
  (t/is (empty? (th/db-query :file-data {:file-id file-id :type "revision"}
                             {::db/remove-deleted false})))
  (t/is (= 0 (:revn (th/db-get :file {:id file-id})))))

(t/deftest stores-the-forward-changes-and-their-inverse-in-the-payload
  (let [[profile file] (create-file)
        commit-1       (add-page-commit (uuid/next) "one")
        commit-2       (add-page-commit (uuid/next) "two")
        out            (update-file :update-file-v2 (:id profile) (:id file)
                                    {:commits [commit-1 commit-2]})
        revision       (revision-row (:id file) 1)
        payload        (payload-row (:id file) (:id revision))]

    (t/is (nil? (:error out)))
    (t/is (some? revision))
    (t/is (some? payload))

    (t/testing "the payload shares the id and the schedule of the revision"
      (t/is (= (:id revision) (:id payload)))
      (t/is (= (:deleted-at revision) (:deleted-at payload)))
      (t/is (some? (:deleted-at payload))))

    (t/testing "the forward list keeps the order of the commits"
      (t/is (= (into (:redo-changes commit-1) (:redo-changes commit-2))
               (:redo-changes (blob/decode (:data payload))))))

    (t/testing "the inverse goes newest commit first"
      (t/is (= (into (:undo-changes commit-2) (:undo-changes commit-1))
               (:undo-changes (blob/decode (:data payload))))))

    (t/testing "the column keeps the forward changes for the deployed readers"
      (t/is (= (into (:redo-changes commit-1) (:redo-changes commit-2))
               (blob/decode (:changes revision)))))))

(t/deftest stores-only-the-accepted-prefix-in-a-partial-save
  (let [[profile file] (create-file)
        file-id        (:id file)
        page-id        (first (get-in file [:data :pages]))
        shape-a        (uuid/next)
        shape-b        (uuid/next)
        commit-1       (commit [(th/add-rect-change page-id shape-a)
                                (th/add-rect-change page-id shape-b)]
                               [{:type :del-obj :page-id page-id :id shape-b}
                                {:type :del-obj :page-id page-id :id shape-a}])
        commit-2       (add-page-commit (uuid/next) "accepted page")
        commit-3       (commit [(set-parent page-id shape-a (uuid/next))]
                               [(set-parent page-id shape-a uuid/zero)])
        ;; The same id on both sides of the cut must not affect the selection.
        commit-4       (assoc (add-page-commit (uuid/next) "rejected page")
                              :commit-id (:commit-id commit-1))
        out            (update-file :update-file-v2 (:id profile) file-id
                                    {:commits [commit-1 commit-2 commit-3 commit-4]})
        rejected       (-> out :result :rejected)
        revision       (revision-row file-id 1)
        payload        (blob/decode (:data (payload-row file-id (:id revision))))
        redo           (into (:redo-changes commit-1) (:redo-changes commit-2))
        undo           (into (:undo-changes commit-2) (:undo-changes commit-1))]
    (t/is (th/success? out))
    (t/is (= [(:commit-id commit-3) (:commit-id commit-4)] (:commit-ids rejected)))
    (t/is (= (:commit-id commit-3) (:failed-commit-id rejected)))
    (t/is (= :referential-integrity (get-in rejected [:error :code])))
    (t/is (= redo (:redo-changes payload)))
    (t/is (= redo (blob/decode (:changes revision))))
    (t/is (= undo (:undo-changes payload)))))

(t/deftest writes-no-revision-when-the-first-commit-is-rejected
  (let [[profile file] (create-file)
        file-id        (:id file)
        page-id        (first (get-in file [:data :pages]))
        shape-id       (uuid/next)
        commit-1       (commit [(th/add-rect-change page-id shape-id)
                                (set-parent page-id shape-id (uuid/next))]
                               [{:type :del-obj :page-id page-id :id shape-id}])
        commit-2       (add-page-commit (uuid/next) "rejected page")
        out            (update-file :update-file-v2 (:id profile) file-id
                                    {:commits [commit-1 commit-2]})
        rejected       (-> out :result :rejected)]
    (t/is (th/success? out))
    (t/is (= [(:commit-id commit-1) (:commit-id commit-2)] (:commit-ids rejected)))
    (t/is (= (:commit-id commit-1) (:failed-commit-id rejected)))
    (t/is (= :referential-integrity (get-in rejected [:error :code])))
    (assert-no-revision file-id)))

(t/deftest stores-an-empty-inverse-when-the-commits-carry-none
  (let [[profile file] (create-file)
        commit         (assoc (add-page-commit (uuid/next) "one")
                              :undo-changes [])
        out            (update-file :update-file-v2 (:id profile) (:id file)
                                    {:commits [commit]})
        revision       (revision-row (:id file) 1)
        payload        (payload-row (:id file) (:id revision))]

    (t/is (nil? (:error out)))
    (t/is (some? payload))
    (t/is (= {:redo-changes (:redo-changes commit) :undo-changes []}
             (blob/decode (:data payload))))))

(t/deftest rejects-a-change-that-does-not-conform
  (let [[profile file] (create-file)
        commit         {:commit-id (uuid/next)
                        :redo-changes [{:type :mod-obj}]
                        :undo-changes []}
        out            (update-file :update-file-v2 (:id profile) (:id file)
                                    {:commits [commit]})]

    (t/is (th/ex-of-type? (:error out) :validation))
    (t/is (nil? (revision-row (:id file) 1)))))

(t/deftest rejects-invalid-undo-before-writing-a-revision
  (let [[profile file] (create-file)
        commit         (assoc (add-page-commit (uuid/next) "page")
                              :undo-changes [{:type :mod-obj}])
        out            (update-file :update-file-v2 (:id profile) (:id file)
                                    {:commits [commit]})]
    (t/is (th/ex-of-type? (:error out) :validation))
    (t/is (th/ex-of-code? (:error out) :params-validation))
    (assert-no-revision (:id file))))

(t/deftest the-old-command-stores-no-inverse
  (let [[profile file] (create-file)
        page-id        (uuid/next)
        redo           [{:type :add-page :id page-id :name "page"}]
        out            (update-file :update-file (:id profile) (:id file)
                                    {:changes redo
                                     :undo-changes [{:type :del-page :id page-id}]})
        revision       (revision-row (:id file) 1)
        payload        (payload-row (:id file) (:id revision))]

    (t/is (nil? (:error out)))
    (t/is (= {:redo-changes redo :undo-changes []}
             (blob/decode (:data payload))))))
