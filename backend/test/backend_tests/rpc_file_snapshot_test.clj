;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-file-snapshot-test
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.pprint :as pp]
   [app.common.thumbnails :as thc]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.features.fdata :as fdata]
   [app.features.file-snapshots :as fsnap]
   [app.http :as http]
   [app.rpc :as-alias rpc]
   [app.storage :as sto]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [cuerdas.core :as str]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- update-file!
  [& {:keys [profile-id file-id changes revn] :or {revn 0}}]
  (let [params {::th/type :update-file
                ::rpc/profile-id profile-id
                :id file-id
                :session-id (uuid/random)
                :revn revn
                :vern 0
                :features cfeat/supported-features
                :changes changes}
        out    (th/command! params)]
    ;; (th/print-result! out)
    (t/is (nil? (:error out)))
    (:result out)))

(t/deftest snapshots-crud
  (let [profile (th/create-profile* 1 {:is-active true})
        team-id (:default-team-id profile)
        proj-id (:default-project-id profile)

        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id proj-id
                                    :is-shared false})
        snapshot-id (volatile! nil)]

    (t/testing "create snapshot"
      (let [params {::th/type :create-file-snapshot
                    ::rpc/profile-id (:id profile)
                    :file-id (:id file)
                    :label "label1"}
            out    (th/command! params)]
        ;; (th/print-result! out)

        (t/is (nil? (:error out)))
        (let [result (:result out)]
          (t/is (= "label1" (:label result)))
          (t/is (uuid? (:id result)))
          (vswap! snapshot-id (constantly (:id result))))))

    (t/testing "list snapshots"
      (let [params {::th/type :get-file-snapshots
                    ::rpc/profile-id (:id profile)
                    :file-id (:id file)}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (let [[row :as result] (:result out)]
          (t/is (= 1 (count result)))
          (t/is (= "label1" (:label row)))
          (t/is (uuid? (:id row)))
          (t/is (= @snapshot-id (:id row)))
          (t/is (= 0 (:revn row)))
          (t/is (= (:id profile) (:profile-id row))))))

    (t/testing "restore snapshot"
      (let [params {::th/type :restore-file-snapshot
                    ::rpc/profile-id (:id profile)
                    :file-id (:id file)
                    :id @snapshot-id}
            out    (th/command! params)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (nil? (:result out))))

      (let [[row1 row2 :as rows]
            (th/db-query :file-change
                         {:file-id (:id file)}
                         {:order-by [:created-at]})]

        (t/is (= 2 (count rows)))
        (t/is (= "user" (:created-by row1)))
        (t/is (= "system" (:created-by row2)))))

    (t/testing "delete snapshot"
      (let [[row1 row2 :as rows]
            (th/db-query :file-change
                         {:file-id (:id file)}
                         {:order-by [:created-at]})]

        (t/testing "delete user created snapshot"
          (let [params {::th/type :delete-file-snapshot
                        ::rpc/profile-id (:id profile)
                        :file-id (:id file)
                        :id (:id row1)}
                out    (th/command! params)]

            ;; (th/print-result! out)
            (t/is (nil? (:error out)))
            (t/is (true? (:result out)))))

        (t/testing "delete system created snapshot"
          (let [params {::th/type :delete-file-snapshot
                        ::rpc/profile-id (:id profile)
                        :file-id (:id file)
                        :id (:id row2)}
                out    (th/command! params)]

            ;; (th/print-result! out)
            (let [error (:error out)
                  data  (ex-data error)]
              (t/is (th/ex-info? error))
              (t/is (= (:type data) :validation))
              (t/is (= (:code data) :system-snapshots-cant-be-deleted)))))

        ;; this will run pending task triggered by deleting user snapshot
        (th/run-pending-jobs)

        (binding [ct/*clock* (ct/fixed-clock (ct/in-future {:days 8}))]
          (let [res (th/run-task! :objects-gc {})]
            ;; delete 2 snapshots and 2 file data entries
            (t/is (= 4 (:processed res)))))))))

(t/deftest snapshot-preview-resolves-fragments
  ;; Regression test (R1): previewing an automatic snapshot of a
  ;; pointer-mapped file must return plain data, not pointer
  ;; placeholders the preview UI cannot render.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        page-id (first (get-in file [:data :pages]))
        shape-id (uuid/random)]

    ;; An update at revn 0 takes an automatic (system) snapshot of the
    ;; pre-change state; with pointer-map that snapshot shares fragments
    ;; with the live file.
    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes [{:type :add-obj
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
                                    :type :rect})}])

    (let [snap-id (->> (th/db-query :file-change {:file-id file-id})
                       (filter #(some? (:label %)))
                       (first)
                       (:id))
          _       (t/is (uuid? snap-id))
          out     (th/command! {::th/type :get-file-snapshot
                                ::rpc/profile-id (:id profile)
                                :file-id file-id
                                :id snap-id
                                :features cfeat/supported-features})]
      (t/is (nil? (:error out)))
      (let [data (get-in (:result out) [:data])]
        (t/is (map? data))
        (t/is (empty? (fdata/get-used-pointer-ids data)))))))

(defn- live-fragment-ids
  [file-id]
  (into #{}
        (comp (filter #(nil? (:deleted-at %)))
              (map :id))
        (th/db-query :file-data {:file-id file-id :type "fragment"})))

(t/deftest user-snapshot-shares-fragments
  ;; User-pinned versions share fragment rows like automatic snapshots
  ;; instead of storing a materialized copy.
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})
        file-id (:id file)
        page-id (first (get-in file [:data :pages]))
        before  (live-fragment-ids file-id)
        out     (th/command! {::th/type :create-file-snapshot
                              ::rpc/profile-id (:id profile)
                              :file-id file-id
                              :label "v1"})
        _       (t/is (nil? (:error out)))
        snap-id (:id (:result out))]

    ;; No new fragment rows: the version reuses the live ones.
    (t/is (uuid? snap-id))
    (t/is (= before (live-fragment-ids file-id)))

    ;; The version pointers are a subset of the live pointers.
    (let [live (fdata/get-used-pointer-ids
                (:data (bfc/get-file th/*system* file-id)))
          snap (fdata/get-used-pointer-ids
                (:data (fsnap/get-snapshot th/*system* file-id snap-id)))]
      (t/is (pos? (count snap)))
      (t/is (every? #(contains? live %) snap)))

    ;; Modify the file, then restore the version and compare resolved
    ;; content with the version preview (both materialized).
    (update-file! :profile-id (:id profile)
                  :file-id file-id
                  :revn 0
                  :changes [{:type :add-obj
                             :page-id page-id
                             :id (uuid/random)
                             :parent-id uuid/zero
                             :frame-id uuid/zero
                             :components-v2 true
                             :obj (cts/setup-shape
                                   {:id (uuid/random)
                                    :name "rect"
                                    :frame-id uuid/zero
                                    :parent-id uuid/zero
                                    :type :rect})}])
    (let [preview-out (th/command! {::th/type :get-file-snapshot
                                    ::rpc/profile-id (:id profile)
                                    :file-id file-id
                                    :id snap-id
                                    :features cfeat/supported-features})
          _           (t/is (nil? (:error preview-out)))
          preview     (:result preview-out)
          restore-out (th/command! {::th/type :restore-file-snapshot
                                    ::rpc/profile-id (:id profile)
                                    :file-id file-id
                                    :id snap-id})
          _           (t/is (nil? (:error restore-out)))
          live        (:data (bfc/get-file th/*system* file-id :realize? true))
          ;; The preview resolves pointers but keeps objects-maps
          ;; encoded; realize them too so both sides are plain data.
          preview-data (:data (fdata/realize-objects
                               nil {:data (:data preview)}))]
      (t/is (= preview-data live)))))

(t/deftest snapshots-locking
  (let [profile-1 (th/create-profile* 1 {:is-active true})
        profile-2 (th/create-profile* 2 {:is-active true})

        team
        (th/create-team* 1 {:profile-id (:id profile-1)})

        project
        (th/create-project* 1 {:profile-id (:id profile-1)
                               :team-id (:id team)})

        file
        (th/create-file* 1 {:profile-id (:id profile-1)
                            :project-id (:id project)
                            :is-shared false})

        snapshot
        (let [params {::th/type :create-file-snapshot
                      ::rpc/profile-id (:id profile-1)
                      :file-id (:id file)
                      :label "label1"}
              out    (th/command! params)]
          ;; (th/print-result! out)

          (t/is (nil? (:error out)))
          (:result out))]

    ;; Add the secont profile to the team
    (th/create-team-role* {:team-id (:id team)
                           :profile-id (:id profile-2)
                           :role :admin})

    (t/testing "lock snapshot"
      (let [params {::th/type :lock-file-snapshot
                    ::rpc/profile-id (:id profile-1)
                    :file-id (:id file)
                    :id (:id snapshot)}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (true? (:result out)))

        (let [snapshot (th/db-get :file-change {:id (:id snapshot)} {::db/remove-deleted false})]
          (t/is (= (:id profile-1) (:locked-by snapshot))))))

    (t/testing "delete locked snapshot"
      (let [params {::th/type :delete-file-snapshot
                    ::rpc/profile-id (:id profile-2)
                    :file-id (:id file)
                    :id (:id snapshot)}
            out    (th/command! params)]

        ;; (th/print-result! out)
        (let [error (:error out)
              data  (ex-data error)]
          (t/is (th/ex-info? error))
          (t/is (= (:type data) :validation))
          (t/is (= (:code data) :snapshot-is-locked)))))

    (t/testing "unlock snapshot"
      (let [params {::th/type :unlock-file-snapshot
                    ::rpc/profile-id (:id profile-1)
                    :file-id (:id file)
                    :id (:id snapshot)}
            out    (th/command! params)]
        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (true? (:result out)))

        (let [snapshot (th/db-get :file-change {:id (:id snapshot)})]
          (t/is (= nil (:locked-by snapshot))))))

    (t/testing "delete locked snapshot"
      (let [params {::th/type :delete-file-snapshot
                    ::rpc/profile-id (:id profile-2)
                    :file-id (:id file)
                    :id (:id snapshot)}
            out    (th/command! params)]

        ;; (th/print-result! out)
        (t/is (nil? (:error out)))
        (t/is (true? (:result out)))))))
