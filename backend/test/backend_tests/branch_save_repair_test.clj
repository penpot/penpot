;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.branch-save-repair-test
  "The auto-repair of `update-file` on a branch save. A branch derives
  its data from its merge base and op log, so a save that repairs
  pre-existing damage has to record the repair in the op log, ahead of
  the client's changes, for the derive and the merge to see it."
  (:require
   [app.common.features :as cfeat]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.file-snapshots :as fsnap]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files-update :as files-update]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- add-rect
  [page-id shape-id]
  {:type :add-obj
   :page-id page-id
   :id shape-id
   :parent-id uuid/zero
   :frame-id uuid/zero
   :components-v2 true
   :obj (cts/setup-shape
         {:id shape-id
          :name "rect"
          :frame-id uuid/zero
          :parent-id uuid/zero
          :type :rect})})

(defn- set-attr
  [page-id shape-id attr val]
  {:type :mod-obj
   :id shape-id
   :page-id page-id
   :operations [{:type :set :attr attr :val val}]})

(defn- update-file
  [profile file-id changes & {:keys [skip-validate]}]
  (let [row (th/db-get :file {:id file-id})]
    (th/command! (cond-> {::th/type :update-file
                          ::rpc/profile-id (:id profile)
                          :id file-id
                          :session-id (uuid/random)
                          :revn (:revn row)
                          :vern (:vern row)
                          :features cfeat/supported-features
                          :changes changes}
                   skip-validate (assoc :skip-validate true)))))

(defn- get-file
  [profile file-id]
  (th/command! {::th/type :get-file
                ::rpc/profile-id (:id profile)
                :id file-id}))

(defn- objects
  [profile file-id page-id]
  (let [out (get-file profile file-id)]
    (t/is (nil? (:error out)))
    (get-in out [:result :data :pages-index page-id :objects])))

(defn- oplog-changes
  [branch-file-id]
  (->> (th/db-query :file-branch-change {:file-id branch-file-id})
       (sort-by :revn)
       (mapv (comp blob/decode :changes))))

(defn- repair-rows
  [file-id]
  (->> (th/db-query :file-change {:file-id file-id
                                  :session-id files-update/repair-session-id}
                    {::db/remove-deleted false})
       (filter :changes)
       (vec)))

(defn- label-rows
  [file-id label]
  (->> (th/db-query :file-change {:file-id file-id :label label}
                    {::db/remove-deleted false})
       (vec)))

(t/deftest branch-save-repairs-damage-from-its-base
  (with-redefs [cf/flags (conj cf/flags :branching :file-validation)]
    (let [profile (th/create-profile* 1 {:is-active true})
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id (:default-project-id profile)
                                      :is-shared false})
          file-id (:id file)
          page-id (first (get-in file [:data :pages]))
          shape-a (uuid/next)
          shape-b (uuid/next)]

      (t/is (th/success? (update-file profile file-id
                                      [(add-rect page-id shape-a)
                                       (add-rect page-id shape-b)])))

      ;; main carries the damage before the fork, so the branch inherits
      ;; it through its merge base
      (t/is (th/success? (update-file profile file-id
                                      [(set-attr page-id shape-a :frame-id (uuid/next))]
                                      :skip-validate true)))

      (let [create         (:result (th/command! {::th/type :create-file-branch
                                                  ::rpc/profile-id (:id profile)
                                                  :file-id file-id
                                                  :name "repair"}))
            branch-id      (:id create)
            branch-file-id (:branch-file-id create)
            branch-revn    (:revn (th/db-get :file {:id branch-file-id}))
            client         [(set-attr page-id shape-b :name "renamed")]
            out            (update-file profile branch-file-id client)]

        (t/testing "the save on the branch succeeds and repairs"
          (t/is (th/success? out))
          (t/is (= 1 (count (repair-rows branch-file-id)))))

        (t/testing "the op log holds the repair changes, then the client's"
          (let [repair (-> (repair-rows branch-file-id) first :changes blob/decode)
                log    (oplog-changes branch-file-id)]
            (t/is (seq repair))
            (t/is (= 1 (count log)))
            (t/is (= (into (vec repair) client) (first log)))))

        (t/testing "the branch stores no data row of its own"
          (t/is (empty? (th/db-query :file-data {:file-id branch-file-id :type "main"}))))

        (t/testing "the repaired state survives a reload of the branch"
          (let [objs  (objects profile branch-file-id page-id)
                frame (get-in objs [shape-a :frame-id])]
            (t/is (contains? objs frame))
            (t/is (= "renamed" (get-in objs [shape-b :name])))))

        (t/testing "the repair snapshot of the branch is readable"
          (let [[row] (label-rows branch-file-id (str "internal/repair/" branch-revn))
                snap  (some->> (:id row) (fsnap/get-snapshot th/*system* branch-file-id))
                objs  (get-in snap [:data :pages-index page-id :objects])]
            (t/is (some? row))
            (t/is (contains? objs shape-a))
            ;; the snapshot keeps the damage the save repaired
            (t/is (not (contains? objs (get-in objs [shape-a :frame-id]))))))

        (t/testing "a second save on the repaired branch needs no repair"
          (t/is (th/success? (update-file profile branch-file-id
                                          [(set-attr page-id shape-b :name "again")])))
          (t/is (= 1 (count (repair-rows branch-file-id)))))

        ;; the merge validates and repairs its own result, so main ends
        ;; valid either way; the branch diff is what shows that the
        ;; repair travels with the branch
        (t/testing "the branch diff carries the repaired shape"
          (let [diff (:result (th/command! {::th/type :get-branch-diff
                                            ::rpc/profile-id (:id profile)
                                            :branch-id branch-id}))]
            (t/is (some #(= shape-a (:id %)) (:changes diff)))))

        (t/testing "the merge carries the repair into main"
          (let [out (th/command! {::th/type :merge-file-branch
                                  ::rpc/profile-id (:id profile)
                                  :branch-id branch-id})]
            (t/is (nil? (:error out)))
            (t/is (= :merged (-> out :result :status))))
          (let [objs  (objects profile file-id page-id)
                frame (get-in objs [shape-a :frame-id])]
            (t/is (contains? objs frame))
            (t/is (= "again" (get-in objs [shape-b :name])))))))))
