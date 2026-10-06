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
   [app.common.features :as cfeat]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.file-snapshots :as fsnap]
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
  "The file ids of the `file-gc` jobs submitted so far. A job's params
  are plain JSON, so the id comes back as a string."
  []
  (->> (th/db-query :job {:name "file-gc"})
       (map (fn [{:keys [params]}]
              (-> (if (db/pgobject? params)
                    (db/decode-json-pgobject params)
                    params)
                  (get :file-id)
                  (uuid/parse))))
       (into #{})))

(defn- branch-row-id
  "The `file_branch` row id of a live branch file."
  [branch-file-id]
  (:id (th/db-get :file-branch {:branch-file-id branch-file-id})))

(defn- apply-change!
  [profile file-id change]
  (let [file (th/db-get :file {:id file-id})
        out  (th/command! {::th/type :update-file
                           ::rpc/profile-id (:id profile)
                           :id file-id
                           :session-id (uuid/random)
                           :revn (:revn file)
                           :vern (:vern file)
                           :features cfeat/supported-features
                           :changes [change]})]
    (t/is (nil? (:error out)))))

(defn- file-data
  "The `:data` of a file as the read path serves it: derived for a
  branch file."
  [profile file-id]
  (let [out (th/command! {::th/type :get-file
                          ::rpc/profile-id (:id profile)
                          :id file-id})]
    (t/is (nil? (:error out)))
    (-> out :result :data)))

(defn- linked-library-ids
  "The libraries a file resolves, as the workspace reads them."
  [profile file-id]
  (let [out (th/command! {::th/type :get-file-libraries
                          ::rpc/profile-id (:id profile)
                          :file-id file-id})]
    (t/is (nil? (:error out)))
    (into #{} (map :id) (:result out))))

(defn- update-from-main!
  [profile branch-file-id]
  (let [out (th/command! {::th/type :update-branch-from-main
                          ::rpc/profile-id (:id profile)
                          :branch-id (branch-row-id branch-file-id)})]
    (t/is (nil? (:error out)))
    (t/is (= :updated (-> out :result :status)))))

(defn- fill-ref-file
  [data {:keys [page-id shape-id]}]
  (get-in data [:pages-index page-id :objects shape-id :fills 0 :fill-color-ref-file]))

(defn- setup-library-use!
  "A shared library publishing one colour, and a main file linked to it
  whose one shape fills with that colour."
  [profile]
  (let [library  (th/create-file* 1 {:profile-id (:id profile)
                                     :project-id (:default-project-id profile)
                                     :is-shared true})
        main     (create-file profile 2)
        color-id (uuid/random)
        shape-id (uuid/random)
        page-id  (-> (file-data profile (:id main)) :pages first)]

    (apply-change! profile (:id library)
                   {:type :add-color
                    :color {:id color-id :name "Brand" :color "#ff0000" :opacity 1}})
    (th/link-file-to-library* {:file-id (:id main) :library-id (:id library)})
    (apply-change! profile (:id main)
                   {:type :add-obj
                    :page-id page-id
                    :id shape-id
                    :parent-id uuid/zero
                    :frame-id uuid/zero
                    :obj (-> (cts/setup-shape
                              {:id shape-id :name "Branded" :type :rect
                               :parent-id uuid/zero :frame-id uuid/zero})
                             (assoc :fills [{:fill-color "#ff0000"
                                             :fill-opacity 1
                                             :fill-color-ref-id color-id
                                             :fill-color-ref-file (:id library)}]))})

    {:library-id (:id library)
     :main-id    (:id main)
     :main       main
     :color-id   color-id
     :page-id    page-id
     :shape-id   shape-id}))

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

(t/deftest unpublish-library-skips-branch
  ;; Unpublishing a library absorbs its assets into the files that use it
  ;; through `bfc/update-file!`, a write a branch must never take. The
  ;; branch keeps its link instead, so the unpublished library still
  ;; resolves there, and its next update from main brings main's
  ;; absorbed copy.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile   (th/create-profile* 1 {:is-active true})
          {:keys [library-id main-id main color-id] :as ids} (setup-library-use! profile)
          branch-id (create-branch! profile main "absorb")
          revn      (:revn (th/db-get :file {:id branch-id}))
          out       (th/command! {::th/type :set-file-shared
                                  ::rpc/profile-id (:id profile)
                                  :id library-id
                                  :is-shared false})]

      (t/is (nil? (:error out)))

      (t/testing "main absorbs the colour and drops its link"
        (let [data (file-data profile main-id)]
          (t/is (some? (get-in data [:colors color-id])))
          (t/is (= main-id (fill-ref-file data ids))))
        (t/is (not (contains? (linked-library-ids profile main-id) library-id))))

      (t/testing "the branch writes nothing and still resolves the library"
        (t/is (empty? (payload-rows branch-id)))
        (t/is (empty? (th/db-query :file-branch-change {:file-id branch-id})))
        (t/is (= revn (:revn (th/db-get :file {:id branch-id}))))
        (t/is (= library-id (fill-ref-file (file-data profile branch-id) ids)))
        (t/is (contains? (linked-library-ids profile branch-id) library-id))
        (t/is (some? (get-in (file-data profile library-id) [:colors color-id]))))

      (t/testing "the next update from main brings main's absorbed copy"
        (update-from-main! profile branch-id)
        (let [data (file-data profile branch-id)]
          (t/is (some? (get-in data [:colors color-id])))
          (t/is (not= library-id (fill-ref-file data ids))))
        (t/is (empty? (payload-rows branch-id)))))))

(t/deftest unpublish-library-skips-deleted-branch
  ;; A deleted branch keeps its own library links until collection, and
  ;; its derive raises once its `file_branch` row carries the deletion.
  ;; The unpublish must not reach it.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile   (th/create-profile* 1 {:is-active true})
          {:keys [library-id main-id main color-id]} (setup-library-use! profile)
          branch-id (create-branch! profile main "deleted")]

      (t/is (nil? (:error (th/command! {::th/type :delete-file-branch
                                        ::rpc/profile-id (:id profile)
                                        :id (branch-row-id branch-id)}))))

      ;; the deletion is pending and the branch still links the library
      (t/is (some? (:deleted-at (th/db-get :file {:id branch-id}
                                           {::db/remove-deleted false}))))
      (t/is (seq (th/db-query :file-library-rel {:file-id branch-id
                                                 :library-file-id library-id})))

      (let [out (th/command! {::th/type :set-file-shared
                              ::rpc/profile-id (:id profile)
                              :id library-id
                              :is-shared false})]
        (t/is (nil? (:error out))))

      (t/is (some? (get-in (file-data profile main-id) [:colors color-id])))
      (t/is (empty? (payload-rows branch-id))))))

(t/deftest delete-library-skips-branch
  ;; The deletion task absorbs a shared library into the files still
  ;; linked to it; a project deletion keeps those links. A branch
  ;; resolves nothing through the soft-deleted library until its next
  ;; update from main brings main's absorbed copy.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile   (th/create-profile* 1 {:is-active true})
          {:keys [library-id main-id main color-id] :as ids} (setup-library-use! profile)
          branch-id (create-branch! profile main "absorb")
          revn      (:revn (th/db-get :file {:id branch-id}))]

      (th/run-task! :delete-object {:object :file
                                    :deleted-at (ct/now)
                                    :id library-id})

      ;; the task logs an absorption error instead of raising it, so
      ;; main's absorbed copy is what proves the absorption ran
      (let [data (file-data profile main-id)]
        (t/is (some? (get-in data [:colors color-id])))
        (t/is (= main-id (fill-ref-file data ids))))

      (t/testing "the branch writes nothing and resolves nothing until the update"
        (t/is (empty? (payload-rows branch-id)))
        (t/is (empty? (th/db-query :file-branch-change {:file-id branch-id})))
        (t/is (= revn (:revn (th/db-get :file {:id branch-id}))))
        (t/is (= library-id (fill-ref-file (file-data profile branch-id) ids)))
        (t/is (not (contains? (linked-library-ids profile branch-id) library-id))))

      (t/testing "the next update from main brings main's absorbed copy"
        (update-from-main! profile branch-id)
        (let [data (file-data profile branch-id)]
          (t/is (some? (get-in data [:colors color-id])))
          (t/is (not= library-id (fill-ref-file data ids))))
        (t/is (empty? (payload-rows branch-id)))))))

(defn- refusal
  "The `ex-data` of what `f` raised, or `:no-error`."
  [f]
  (try
    (f)
    :no-error
    (catch Throwable cause
      (or (ex-data cause) (some-> (ex-cause cause) ex-data)))))

(t/deftest restore!-refuses-branch-file
  ;; The socket-REPL restore of one file
  ;; (`app.srepl.main/restore-file-snapshot!`) calls `fsnap/restore!`
  ;; directly, past the check of the RPC command.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile   (th/create-profile* 1 {:is-active true})
          file      (create-file profile 1)
          branch-id (create-branch! profile file "restore-me")
          snap      (:result (th/command! {::th/type :create-file-snapshot
                                           ::rpc/profile-id (:id profile)
                                           :file-id branch-id
                                           :label "branch-version"}))
          revn      (:revn (th/db-get :file {:id branch-id}))
          error     (refusal #(db/tx-run! th/*system* fsnap/restore! branch-id (:id snap)))]

      (t/is (uuid? (:id snap)))
      (t/is (= :validation (:type error)))
      (t/is (= :branch-file-cant-be-restored (:code error)))
      (t/is (empty? (payload-rows branch-id)))
      (t/is (= revn (:revn (th/db-get :file {:id branch-id})))))))

(t/deftest restore-team-snapshot!-refuses-branch-file
  ;; The socket-REPL team restore reaches every file of the team,
  ;; branch files included, through `fsnap/restore!`.
  (with-redefs [cf/flags (conj cf/flags :branching)]
    (let [profile   (th/create-profile* 1 {:is-active true})
          team-id   (:default-team-id profile)
          file      (create-file profile 1)
          branch-id (create-branch! profile file "team-restore")]

      ;; the team restore wants the label on every file of the team
      (doseq [file-id [(:id file) branch-id]]
        (t/is (nil? (:error (th/command! {::th/type :create-file-snapshot
                                          ::rpc/profile-id (:id profile)
                                          :file-id file-id
                                          :label "before"})))))

      (let [revn  (:revn (th/db-get :file {:id (:id file)}))
            error (refusal #(db/tx-run! th/*system* h/restore-team-snapshot! team-id "before"))]
        (t/is (= :validation (:type error)))
        (t/is (= :branch-file-cant-be-restored (:code error)))
        (t/is (empty? (payload-rows branch-id)))
        ;; the refusal rolls the whole team restore back
        (t/is (= revn (:revn (th/db-get :file {:id (:id file)}))))))))
