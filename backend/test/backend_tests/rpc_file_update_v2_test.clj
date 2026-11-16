;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-file-update-v2-test
  "The `update-file-v2` request contract: one commit list where every commit
  carries its forward and inverse changes, and the identity of the command
  it replaces (audit event name and limits)."
  (:require
   [app.common.features :as cfeat]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.loggers.audit :as audit]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as-alias climit]
   [app.rpc.rlimit :as-alias rlimit]
   [app.util.blob :as blob]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(defn- with-validation
  [next]
  (with-redefs [cf/flags (conj cf/flags :file-validation :file-schema-validation)]
    (next)))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset with-validation)

(defn- create-file
  []
  (let [profile (th/create-profile* 1)
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:default-project-id profile)
                                    :is-shared false})]
    {:profile-id (:id profile)
     :file-id    (:id file)
     :page-id    (-> file :data :pages first)}))

(defn- params
  [{:keys [profile-id file-id]} type values]
  (merge {::th/type type
          ::rpc/profile-id profile-id
          :id file-id
          :session-id (uuid/next)
          :revn 0
          :vern 0
          :features cfeat/supported-features}
         values))

(defn- save
  ([ctx values] (save ctx :update-file-v2 values))
  ([ctx type values]
   (th/command! (params ctx type values))))

(defn- commit
  [redo undo]
  {:commit-id (uuid/next) :redo-changes redo :undo-changes undo})

(defn- break-parent
  "A change that leaves the shape pointing to a parent that does not exist."
  [page-id id]
  {:type :mod-obj
   :page-id page-id
   :id id
   :operations [{:type :set :attr :parent-id :val (uuid/next)}]})

(defn- get-file-changes
  [{:keys [file-id]}]
  (->> (th/db-query :file-change {:file-id file-id} {:order-by [:revn]})
       (filter :changes)
       (mapv #(update % :changes blob/decode))))

(defn- mdata-of
  [type]
  (first (get-in th/*system* [:app.rpc/methods type])))

(t/deftest rejects-the-shape-of-the-old-contract
  (let [{:keys [page-id] :as ctx} (create-file)
        redo [{:type :add-page :id (uuid/next) :name "page"}]]
    (doseq [[key value] {:changes redo
                         :changes-with-metadata [{:changes redo}]
                         :undo-changes []
                         :origin :local}]
      (t/testing (str "rejects " key)
        (let [out (save ctx (assoc {:commits [(commit redo [])]} key value))]
          (t/is (th/ex-of-type? (:error out) :validation))
          (t/is (th/ex-of-code? (:error out) :params-validation)))))

    (t/testing "nothing was written"
      (t/is (empty? (get-file-changes ctx)))
      (t/is (= 0 (:revn (th/db-get :file {:id (:file-id ctx)})))))))

(t/deftest rejects-a-save-without-commits
  (let [ctx (create-file)
        out (save ctx {:commits []})]
    (t/is (th/ex-of-type? (:error out) :validation))
    (t/is (th/ex-of-code? (:error out) :params-validation))))

(t/deftest saves-the-commits-before-the-first-invalid-one
  (let [{:keys [page-id] :as ctx} (create-file)
        shape-a  (uuid/next)
        shape-c  (uuid/next)
        commit-1 (commit [(th/add-rect-change page-id shape-a)] [])
        commit-2 (commit [(break-parent page-id shape-a)] [])
        commit-3 (commit [(th/add-rect-change page-id shape-c)] [])
        out      (save ctx {:commits [commit-1 commit-2 commit-3]})]

    (t/is (nil? (:error out)))

    (t/testing "the response names the rejected commits and the failure"
      (let [rejected (-> out :result :rejected)]
        (t/is (= [(:commit-id commit-2) (:commit-id commit-3)] (:commit-ids rejected)))
        (t/is (= (:commit-id commit-2) (:failed-commit-id rejected)))
        (t/is (= :validation (-> rejected :error :type)))
        (t/is (= :referential-integrity (-> rejected :error :code)))))

    (t/testing "the change log holds the accepted commit only"
      (let [rows (get-file-changes ctx)]
        (t/is (= 1 (count rows)))
        (t/is (= 1 (:revn (first rows))))
        (t/is (= (:redo-changes commit-1) (:changes (first rows))))))))

(t/deftest keeps-the-audit-event-name-of-the-command-it-replaces
  (let [{:keys [page-id] :as ctx} (create-file)
        redo   [(th/add-rect-change page-id (uuid/next))]
        result (th/command-raw!
                (params ctx :update-file-v2
                        {:commits [(commit redo [])]}))]

    (t/testing "the audit log, the webhooks and the telemetry see one event"
      (t/is (= "update-file" (::audit/name (meta result)))))

    (t/testing "the event keeps the props the command replaces"
      (t/is (some? (::audit/replace-props (meta result)))))))

(t/deftest declares-the-same-limits-as-the-old-command
  (t/testing "the concurrency limiter is shared, so both commands queue together"
    (t/is (= (::climit/id (mdata-of :update-file))
             (::climit/id (mdata-of :update-file-v2)))))

  (t/testing "the rate limiter keeps one bucket and one counter per profile"
    (t/is (= "update-file" (::rlimit/method (mdata-of :update-file-v2))))))
