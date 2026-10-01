;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.branch-payload-writers-test
  "Regression tests for the writers that must never persist a branch
  file's `file_data` payload: `branch-file-data` derives the branch
  from its merge base snapshot plus the op log, so a persisted main row
  is invisible to every read path and silently desynchronises the
  derived state."
  (:require
   [app.common.time :as ct]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.srepl.helpers :as h]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(defn- create-branch!
  [profile file name]
  (let [out (th/command! {::th/type :create-file-branch
                          ::rpc/profile-id (:id profile)
                          :file-id (:id file)
                          :name name})]
    (t/is (nil? (:error out)))
    (:branch-file-id (:result out))))

(defn- create-file
  [profile index]
  (th/create-file* index {:profile-id (:id profile)
                          :project-id (:default-project-id profile)
                          :is-shared false}))

(defn- payload-rows
  "The `file_data` main rows of a file: the payload a branch file must
  never have."
  [file-id]
  (th/db-query :file-data {:file-id file-id :type "main"}))

(defn- scheduled-gc-file-ids
  "The file ids of the `file-gc` tasks submitted so far."
  []
  (->> (th/db-query :task {:name "file-gc"})
       (map (fn [{:keys [props]}]
              (-> (if (db/pgobject? props)
                    (db/decode-transit-pgobject props)
                    props)
                  (get :file-id))))
       (into #{})))

(t/deftest process-file!-refuses-branch-file
  ;; The admin repair entry points (`app.http.debug` file-repair and
  ;; `app.srepl.main/repair-file!`) both run through `process-file!`,
  ;; which persists through `bfc/update-file!`; on a branch that writes
  ;; a payload row no read path consults.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile   (th/create-profile* 1 {:is-active true})
          file      (create-file profile 1)
          branch-id (create-branch! profile file "repair-me")

          error
          (try
            ;; same shape as `app.srepl.main/repair-file!` and the
            ;; debug file-repair route: the helper runs on a
            ;; transaction-bound system
            (db/tx-run! th/*system*
                        h/process-file!
                        branch-id
                        (fn [file _] (assoc file :name "repaired")))
            :no-error
            (catch Throwable cause
              (or (ex-data cause) (some-> (ex-cause cause) ex-data))))]

      (t/is (uuid? branch-id))
      (t/is (= :validation (:type error)))
      (t/is (= :branch-file-cant-be-processed (:code error)))
      (t/is (empty? (payload-rows branch-id))))))

(t/deftest file-gc-scheduler-never-selects-branch-files
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile   (th/create-profile* 1 {:is-active true})
          file      (create-file profile 1)
          branch-id (create-branch! profile file "gc-candidate")
          threshold (ct/in-past {:days 8})]

      ;; both files look eligible: untrimmed and idle past the delay
      (doseq [file-id [(:id file) branch-id]]
        (th/db-update! :file
                       {:modified-at threshold
                        :has-media-trimmed false}
                       {:id file-id}))

      (t/is (map? (th/run-task! :file-gc-scheduler {})))

      (t/is (= #{(:id file)} (scheduled-gc-file-ids))))))

(t/deftest file-gc-task-skips-branch-file
  ;; Defensive counterpart of the scheduler guard: a file-gc run that
  ;; reaches a branch file anyway must clean nothing and persist
  ;; nothing.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile   (th/create-profile* 1 {:is-active true})
          file      (create-file profile 1)
          branch-id (create-branch! profile file "gc-skip")]

      (th/db-update! :file {:has-media-trimmed false} {:id branch-id})

      (let [result (th/run-task! :file-gc {:file-id branch-id})]
        (t/is (false? result))
        (t/is (empty? (payload-rows branch-id)))
        (t/is (false? (:has-media-trimmed (th/db-get :file {:id branch-id}))))))))
