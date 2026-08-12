;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-file-branch-oplog-test
  "The op-log storage model: a branch file stores no data payload. Its
  `:data` is derived on read by replaying `file_branch_change` over the
  pinned merge-base snapshot, and branch saves append ops instead of
  persisting file data. These tests assert the storage and derivation
  invariants on top of the behaviour covered by
  `backend-tests.rpc-file-branch-test`."
  (:require
   [app.common.features :as cfeat]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.rpc :as-alias rpc]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.string :as str]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- apply-change* [profile file-id change]
  (let [f (th/db-get :file {:id file-id})]
    (th/command! {::th/type :update-file
                  ::rpc/profile-id (:id profile)
                  :id file-id
                  :session-id (uuid/random)
                  :revn (:revn f)
                  :vern (:vern f)
                  :features cfeat/supported-features
                  :changes [change]})))

(defn- create-branch*
  [profile file-id name]
  (:result (th/command! {::th/type :create-file-branch
                         ::rpc/profile-id (:id profile)
                         :file-id file-id
                         :name name})))

(defn- oplog-rows [branch-file-id]
  (th/db-query :file-branch-change {:file-id branch-file-id}))

(defn- stored-data-rows [file-id]
  (th/db-query :file-data {:file-id file-id}))

(t/deftest branch-stores-no-data-and-derives-state
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (create-branch* profile (:id file) "oplog-empty")
          branch-file-id (:branch-file-id create)]

      (t/testing "the branch file stores no real data"
        (let [rows (stored-data-rows branch-file-id)]
          (t/is (= 1 (count rows)))
          (t/is (empty? (-> rows first :data blob/decode :pages)))))

      (t/testing "the op log starts empty"
        (t/is (empty? (oplog-rows branch-file-id))))

      (t/testing "the derived state equals main"
        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id})]
          (t/is (nil? (:error out)))
          (let [mf (th/command! {::th/type :get-file
                                 ::rpc/profile-id (:id profile)
                                 :id (:id file)})]
            (t/is (= (-> mf :result :data :pages-index)
                     (-> out :result :data :pages-index)))))))))

(t/deftest branch-save-appends-ops-and-never-persists-data
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (create-branch* profile (:id file) "oplog-save")
          branch-file-id (:branch-file-id create)
          color-id (uuid/random)]

      (apply-change* profile branch-file-id
                     {:type :add-color
                      :color {:id color-id :name "C" :color "#112233" :opacity 1}})

      (t/testing "one op log row per save"
        (let [rows (oplog-rows branch-file-id)]
          (t/is (= 1 (count rows)))
          (t/is (= 1 (:revn (first rows))))
          (t/is (= [{:type :add-color
                     :color {:id color-id :name "C" :color "#112233" :opacity 1}}]
                   (blob/decode (:changes (first rows)))))))

      (t/testing "the stored data payload never grew"
        (let [rows (stored-data-rows branch-file-id)]
          (t/is (= 1 (count rows)))
          (t/is (empty? (-> rows first :data blob/decode :pages)))))

      (t/testing "the derived state carries the branch edit"
        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id})]
          (t/is (nil? (:error out)))
          (t/is (contains? (-> out :result :data :colors) color-id)))))))

(t/deftest update-from-main-squashes-the-op-log
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (create-branch* profile (:id file) "oplog-update")
          branch-file-id (:branch-file-id create)
          branch-id      (:id create)
          main-color (uuid/random)
          branch-color (uuid/random)]

      ;; main gains a color after the fork
      (apply-change* profile (:id file)
                     {:type :add-color
                      :color {:id main-color :name "Main" :color "#00ff00" :opacity 1}})

      ;; the branch gains its own color (so the squash is non-empty)
      (apply-change* profile branch-file-id
                     {:type :add-color
                      :color {:id branch-color :name "Branch" :color "#ff0000" :opacity 1}})

      (let [out (th/command! {::th/type :update-branch-from-main
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id})]
        (t/is (nil? (:error out)))
        (t/is (= :updated (-> out :result :status))))

      (t/testing "the op log is replaced by the branch-only net"
        (let [rows (oplog-rows branch-file-id)
              ops  (mapv (comp blob/decode :changes) rows)]
          (t/is (= 1 (count rows)))
          (t/is (some #(and (= :add-color (:type %))
                            (= branch-color (get-in % [:color :id])))
                      ops))
          (t/is (not-any? #(and (= :add-color (:type %))
                                (= main-color (get-in % [:color :id])))
                          ops))))

      (t/testing "the derived state has both colors, main's through the base"
        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id})]
          (t/is (nil? (:error out)))
          (let [colors (-> out :result :data :colors)]
            (t/is (contains? colors main-color))
            (t/is (contains? colors branch-color)))))

      (t/testing "the merge base moved to main's revn"
        (let [[row] (th/db-query :file-branch {:id branch-id})
              mf    (th/db-get :file {:id (:id file)})]
          (t/is (= (:revn mf) (:base-revn row))))))))

(t/deftest merge-with-keep-branch-leaves-the-branch-readable
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile (th/create-profile* 1 {:is-active true})
          proj-id (:default-project-id profile)
          file    (th/create-file* 1 {:profile-id (:id profile)
                                      :project-id proj-id
                                      :is-shared false})
          create  (create-branch* profile (:id file) "oplog-keep")
          branch-file-id (:branch-file-id create)
          branch-id      (:id create)
          color-id (uuid/random)]

      (apply-change* profile branch-file-id
                     {:type :add-color
                      :color {:id color-id :name "C" :color "#112233" :opacity 1}})

      (let [out (th/command! {::th/type :merge-file-branch
                              ::rpc/profile-id (:id profile)
                              :branch-id branch-id
                              :keep-branch true})]
        (t/is (nil? (:error out)))
        (t/is (= :merged (-> out :result :status))))

      (t/testing "the kept branch still derives its state"
        (let [out (th/command! {::th/type :get-file
                                ::rpc/profile-id (:id profile)
                                :id branch-file-id})]
          (t/is (nil? (:error out)))
          (t/is (contains? (-> out :result :data :colors) color-id))))

      (t/testing "the base pin survives while the branch is kept"
        (let [rows (->> (th/db-query :file-change {:file-id (:id file)})
                        (filter #(some-> (:label %) (str/starts-with? "branch-base/"))))]
          (t/is (seq rows))
          (t/is (every? (fn [{:keys [deleted-at]}]
                          (or (nil? deleted-at)
                              (ct/is-after? deleted-at (ct/in-future {:days 400}))))
                        rows)))))))
