;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-file-branch-test
  (:require
   [app.binfile.common :as bfc]
   [app.common.features :as cfeat]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(def ^:private page-id (uuid/custom 1 1))
(def ^:private shape-id (uuid/custom 2 1))

(defn- update-file!
  [profile-id file-id changes]
  (let [out (th/command! {::th/type :update-file
                          ::rpc/profile-id profile-id
                          :id file-id
                          :session-id (uuid/random)
                          :revn 0
                          :vern 0
                          :features cfeat/supported-features
                          :changes changes})]
    (t/is (nil? (:error out)))
    (:result out)))

(defn- set-attr
  [attr val]
  [{:type :mod-obj
    :page-id page-id
    :id shape-id
    :operations [{:type :set :attr attr :val val}]}])

(defn- prepare-library
  [profile is-shared]
  (let [file (th/create-file* 1 {:profile-id (:id profile)
                                 :project-id (:default-project-id profile)
                                 :is-shared is-shared})]
    (update-file! (:id profile) (:id file)
                  [{:type :add-page :name "Page" :id page-id}
                   {:type :add-obj
                    :page-id page-id
                    :id shape-id
                    :parent-id uuid/zero
                    :frame-id uuid/zero
                    :components-v2 true
                    :obj (cts/setup-shape {:id shape-id
                                           :name "Rect"
                                           :frame-id uuid/zero
                                           :parent-id uuid/zero
                                           :type :rect})}])
    file))

(defn- command!
  [profile type params]
  (th/command! (merge {::th/type type ::rpc/profile-id (:id profile)} params)))

(defn- get-shape
  [file-id]
  (-> (bfc/get-file th/*system* file-id :realize? true)
      (get-in [:data :pages-index page-id :objects shape-id])))

(t/deftest a-branch-can-not-be-branched
  (let [profile (th/create-profile* 1 {:is-active true})
        file    (prepare-library profile false)
        branch  (:result (command! profile :create-file-branch {:file-id (:id file) :name "b1"}))
        out     (command! profile :create-file-branch {:file-id (:file-id branch) :name "b2"})]
    (t/is (uuid? (:file-id branch)))
    (t/is (= :file-is-branch (th/ex-code (:error out))))))

(t/deftest branch-and-merge
  (let [profile (th/create-profile* 1 {:is-active true})
        core    (prepare-library profile true)
        out     (command! profile :create-file-branch {:file-id (:id core) :name "b1"})
        branch  (:result out)]
    (t/is (nil? (:error out)))

    (t/testing "the branch keeps shape ids and is not shared"
      (let [file (bfc/get-file th/*system* (:file-id branch))]
        (t/is (false? (:is-shared file)))
        (t/is (= "Rect" (:name (get-shape (:file-id branch)))))))

    (update-file! (:id profile) (:file-id branch) (set-attr :name "From branch"))
    (update-file! (:id profile) (:id core) (set-attr :opacity 0.5))

    (t/testing "branch info names its core file"
      (let [{:keys [result error]} (command! profile :get-file-branches {:file-id (:file-id branch)})]
        (t/is (nil? error))
        (t/is (= {:id (:id core) :name (:name core) :project-id (:project-id core) :is-shared true}
                 (:core result)))
        (t/is (= [(:file-id branch)] (map :file-id (:branches result))))))

    (t/testing "diff lists the branch change and no conflict"
      (let [{:keys [result error]} (command! profile :get-file-branch-diff {:file-id (:file-id branch)})]
        (t/is (nil? error))
        (t/is (empty? (:conflicts result)))
        (t/is (= [[:shape :mod]] (map (juxt :kind :op) (:changes result))))))

    (t/testing "merge keeps both edits"
      (let [{:keys [error]} (command! profile :merge-file-branch {:file-id (:file-id branch)})
            shape (get-shape (:id core))]
        (t/is (nil? error))
        (t/is (= "From branch" (:name shape)))
        (t/is (= 0.5 (:opacity shape)))
        (t/is (= "merged" (:status (db/get th/*system* :file-branch {:id (:id branch)}))))))))

(t/deftest merge-requires-resolutions
  (let [profile (th/create-profile* 1 {:is-active true})
        core    (prepare-library profile true)
        branch  (:result (command! profile :create-file-branch {:file-id (:id core) :name "b1"}))]

    (update-file! (:id profile) (:file-id branch) (set-attr :name "Theirs"))
    (update-file! (:id profile) (:id core) (set-attr :name "Ours"))

    (let [{:keys [result]} (command! profile :get-file-branch-diff {:file-id (:file-id branch)})
          [conflict] (:conflicts result)]
      (t/is (= 1 (count (:conflicts result))))

      (let [{:keys [error]} (command! profile :merge-file-branch {:file-id (:file-id branch)})]
        (t/is (= :unresolved-conflicts (th/ex-code error))))

      (let [{:keys [error]} (command! profile :merge-file-branch
                                      {:file-id (:file-id branch)
                                       :resolutions {(:id conflict) "theirs"}})]
        (t/is (nil? error))
        (t/is (= "Theirs" (:name (get-shape (:id core)))))))))

(t/deftest delete-branch
  (let [profile (th/create-profile* 1 {:is-active true})
        core    (prepare-library profile false)
        branch  (:result (command! profile :create-file-branch {:file-id (:id core) :name "b1"}))]

    (command! profile :merge-file-branch {:file-id (:file-id branch)})

    (let [{:keys [result error]} (command! profile :delete-file-branch {:file-id (:file-id branch)})]
      (t/is (nil? error))
      (t/is (= (:id core) (:core-file-id result))))

    (t/is (nil? (db/get* th/*system* :file-branch {:id (:id branch)})))
    (t/is (some? (:deleted-at (db/get* th/*system* :file {:id (:file-id branch)}
                                       {::db/remove-deleted false}))))
    (t/is (empty? (:branches (:result (command! profile :get-file-branches {:file-id (:id core)})))))))

(t/deftest selective-merge
  (let [profile (th/create-profile* 1 {:is-active true})
        core    (prepare-library profile false)
        branch  (:result (command! profile :create-file-branch {:file-id (:id core) :name "b1"}))]

    (update-file! (:id profile) (:file-id branch) (set-attr :name "Theirs"))

    (let [{:keys [result]} (command! profile :get-file-branch-diff {:file-id (:file-id branch)})
          keys (into #{} (map :key) (:changes result))]

      (t/testing "excluding every change is refused"
        (let [{:keys [error]} (command! profile :merge-file-branch {:file-id (:file-id branch)
                                                                    :excluded keys})]
          (t/is (= :nothing-to-merge (th/ex-code error)))))

      (t/testing "an included change is merged"
        (let [{:keys [error]} (command! profile :merge-file-branch {:file-id (:file-id branch)
                                                                    :excluded #{}})]
          (t/is (nil? error))
          (t/is (= "Theirs" (:name (get-shape (:id core))))))))))

(t/deftest dashboard-lists-hide-branches
  (let [profile (th/create-profile* 1 {:is-active true})
        core    (prepare-library profile false)
        branch  (:result (command! profile :create-file-branch {:file-id (:id core) :name "b1"}))
        files   (:result (command! profile :get-project-files {:project-id (:default-project-id profile)}))
        recent  (:result (command! profile :get-team-recent-files {:team-id (:default-team-id profile)}))]
    (t/is (= [(:id core)] (map :id files)))
    (t/is (= [1] (map :branch-count files)))
    (t/is (= [(:id core)] (map :id recent)))
    (t/is (not-any? #(= (:file-id branch) (:id %)) recent))))
