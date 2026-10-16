;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-admin-restore-test
  "Tests for the superuser-guarded restore commands."
  (:require
   [app.auth :as-alias auth]
   [app.common.data :as d]
   [app.common.time :as ct]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.object-cascade :as cascade]
   [app.http :as-alias http]
   [app.rpc :as rpc]
   [app.util.services :as sv]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; ----------------------------------------------------------------
;; Helpers
;; ----------------------------------------------------------------

(defn- raw-method
  "The raw [f mdata] behind `cmd-name` in the admin namespace."
  [cmd-name]
  (some (fn [[f mdata]]
          (when (= cmd-name (::sv/name mdata))
            [f mdata]))
        (sv/scan-ns 'app.rpc.admin.file
                    'app.rpc.admin.project
                    'app.rpc.admin.team
                    'app.rpc.admin.profile)))

(defn- call
  "Invoke an admin command through the real wrapper chain with a
  hand-built config.

  The chain closes over its cfg at startup, so the superuser set is
  injected per call here instead of going through `th/command!`.
  Qualified keys stay in `params` as server context; the rest goes
  into the request, where params validation reads it from."
  [superusers auth-type profile-id token-perms cmd params]
  (let [[f mdata]   (raw-method cmd)
        cfg         (assoc th/*system* ::auth/superusers superusers)
        request     (assoc (th/make-dummy-request)
                           :params (d/without-qualified params))
        server      (with-meta params {::rpc/request-at (ct/now)
                                       ::http/request request})
        wrapped     (#'rpc/wrap cfg f mdata)]
    (wrapped cfg (assoc server
                        ::rpc/profile-id profile-id
                        ::rpc/auth-type auth-type
                        ::rpc/token-perms token-perms))))

(defn- as-session
  [superusers profile]
  (fn [cmd params]
    (call superusers :session (:id profile) #{} cmd params)))

(defn- as-superuser
  [profile]
  (as-session #{(:id profile)} profile))

(defn- caught-code
  "Run `thunk` and return the error code it raised, or `::no-throw`."
  [thunk]
  (try
    (thunk)
    ::no-throw
    (catch clojure.lang.ExceptionInfo cause
      (th/ex-code cause))))

(defn- create-file
  [i]
  (let [profile (th/create-profile* i)
        team    (th/create-team* i {:profile-id (:id profile)})
        project (th/create-project* i {:profile-id (:id profile)
                                       :team-id (:id team)})
        file    (th/create-file* i {:profile-id (:id profile)
                                    :project-id (:id project)})]
    {:profile profile :team team :project project :file file}))

(defn- deleted-at
  "Raw `deleted_at`, bypassing the soft-delete filter."
  [table match]
  (:deleted-at (db/get* th/*system* table match {::db/remove-deleted false})))

(defn- delete-object
  "Delete through the shared cascade, exercising the same path as
  the worker."
  [object id]
  (db/tx-run! th/*system* cascade/update-cascade object id {:deleted-at (ct/now)}))

;; ----------------------------------------------------------------
;; Guard
;; ----------------------------------------------------------------

(t/deftest unlisted-session-rejected
  (let [profile (th/create-profile* 1)
        run     (as-session #{} profile)
        missing (uuid/next)]
    (t/is (= :superuser-required
             (caught-code #(run "restore-file" {:id missing}))))
    (t/is (= :superuser-required
             (caught-code #(run "restore-project" {:id missing}))))
    (t/is (= :superuser-required
             (caught-code #(run "restore-team" {:id missing}))))
    (t/is (= :superuser-required
             (caught-code #(run "restore-profile" {:id missing}))))))

;; ----------------------------------------------------------------
;; Not found
;; ----------------------------------------------------------------

(t/deftest restore-unknown-ids-give-not-found
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)
        missing (uuid/next)]
    (t/is (= :file-not-found
             (caught-code #(run "restore-file" {:id missing}))))
    (t/is (= :project-not-found
             (caught-code #(run "restore-project" {:id missing}))))
    (t/is (= :team-not-found
             (caught-code #(run "restore-team" {:id missing}))))
    (t/is (= :profile-not-found
             (caught-code #(run "restore-profile" {:id missing}))))))

;; ----------------------------------------------------------------
;; Delete keeps the earliest date
;; ----------------------------------------------------------------

(t/deftest delete-team-keeps-earlier-child-timestamp
  ;; Rows deleted earlier keep their date so a later parent delete
  ;; never postpones their purge.
  (let [{:keys [team project file]} (create-file 1)
        past (-> (ct/now) (ct/minus {:hours 1}) (ct/truncate :millisecond))]
    (db/update! th/*system* :project {:deleted-at past} {:id (:id project)})
    (db/update! th/*system* :file {:deleted-at past} {:id (:id file)})
    (db/update! th/*system* :file-data {:deleted-at past} {:file-id (:id file)})
    (delete-object :team (:id team))
    (t/is (= past (deleted-at :project {:id (:id project)})))
    (t/is (= past (deleted-at :file {:id (:id file)})))
    (t/is (= past (deleted-at :file-data {:file-id (:id file)})))
    (t/is (some? (deleted-at :team {:id (:id team)})))))

(t/deftest delete-team-brings-forward-later-child-timestamp
  ;; Rows with a later date take the parent operation date so no
  ;; child outlives its parent's purge.
  (let [{:keys [team file]} (create-file 1)
        future (-> (ct/now) (ct/plus {:hours 1}) (ct/truncate :millisecond))]
    (db/update! th/*system* :file {:deleted-at future} {:id (:id file)})
    (delete-object :team (:id team))
    (let [team-at (deleted-at :team {:id (:id team)})
          file-at (deleted-at :file {:id (:id file)})]
      (t/is (= team-at file-at))
      (t/is (ct/is-before? file-at future)))))

;; ----------------------------------------------------------------
;; File restores its chain up to the team
;; ----------------------------------------------------------------

(t/deftest restore-file-restores-project-and-team-chain
  (let [{:keys [profile team project file]} (create-file 1)
        _     (delete-object :team (:id team))
        run   (as-superuser profile)
        out   (run "restore-file" {:id (:id file)})]
    (t/is (= (:id file) (:id out)))
    (t/is (not (contains? out :recursive)))
    (t/is (nil? (deleted-at :file {:id (:id file)})))
    (t/is (nil? (deleted-at :project {:id (:id project)})))
    (t/is (nil? (deleted-at :team {:id (:id team)})))
    (t/is (some? (deleted-at :file-data {:file-id (:id file)})))
    (th/run-pending-jobs)
    (t/is (nil? (deleted-at :file-data {:file-id (:id file)})))))

;; ----------------------------------------------------------------
;; Project restores its team
;; ----------------------------------------------------------------

(t/deftest restore-project-restores-team-and-files
  (let [{:keys [profile team project file]} (create-file 1)
        _     (delete-object :team (:id team))
        run   (as-superuser profile)
        out   (run "restore-project" {:id (:id project)})]
    (t/is (= (:id project) (:id out)))
    (t/is (not (contains? out :recursive)))
    (t/is (nil? (deleted-at :project {:id (:id project)})))
    (t/is (nil? (deleted-at :team {:id (:id team)})))
    (t/is (some? (deleted-at :file {:id (:id file)})))
    (th/run-pending-jobs)
    (t/is (nil? (deleted-at :file {:id (:id file)})))
    (t/is (nil? (deleted-at :file-data {:file-id (:id file)})))))

;; ----------------------------------------------------------------
;; Team restores its cascade
;; ----------------------------------------------------------------

(t/deftest restore-team-restores-cascade
  (let [{:keys [profile team project file]} (create-file 1)
        _     (delete-object :team (:id team))
        run   (as-superuser profile)
        out   (run "restore-team" {:id (:id team)})]
    (t/is (= (:id team) (:id out)))
    (t/is (not (contains? out :recursive)))
    (t/is (nil? (deleted-at :team {:id (:id team)})))
    (t/is (some? (deleted-at :project {:id (:id project)})))
    (th/run-pending-jobs)
    (t/is (nil? (deleted-at :project {:id (:id project)})))
    (t/is (nil? (deleted-at :file {:id (:id file)})))
    (t/is (nil? (deleted-at :file-data {:file-id (:id file)})))))

(t/deftest restore-team-row-sync-cascade-async
  ;; The team's own row reads back instantly; the children cascade
  ;; runs as a worker task afterwards. Same split as delete.
  (let [{:keys [profile team project file]} (create-file 1)
        _     (delete-object :team (:id team))
        run   (as-superuser profile)
        out   (run "restore-team" {:id (:id team)})]
    (t/is (= (:id team) (:id out)))
    (t/is (nil? (deleted-at :team {:id (:id team)})))
    (t/is (some? (deleted-at :project {:id (:id project)})))
    (th/run-pending-jobs)
    (t/is (nil? (deleted-at :project {:id (:id project)})))
    (t/is (nil? (deleted-at :file {:id (:id file)})))))

;; ----------------------------------------------------------------
;; Profile restores its teams, never the other way round
;; ----------------------------------------------------------------

(t/deftest restore-profile-restores-owned-teams
  (let [profile (th/create-profile* 1)
        team    (th/create-team* 1 {:profile-id (:id profile)})
        _       (delete-object :profile (:id profile))
        run     (as-superuser profile)
        out     (run "restore-profile" {:id (:id profile)})]
    (t/is (= (:id profile) (:id out)))
    (t/is (not (contains? out :recursive)))
    (t/is (nil? (deleted-at :profile {:id (:id profile)})))
    (t/is (some? (deleted-at :team {:id (:id team)})))
    (th/run-pending-jobs)
    (t/is (nil? (deleted-at :team {:id (:id team)})))))

;; ----------------------------------------------------------------
;; No live owner, no restore
;; ----------------------------------------------------------------

(t/deftest restore-file-refuses-team-without-live-owner
  ;; The owner profile is deleted, so restoring the file would leave
  ;; it in an ownerless team: fail before writing anything.
  (let [{:keys [profile team project file]} (create-file 1)
        _     (delete-object :profile (:id profile))
        run   (as-superuser profile)]
    (t/is (= :team-has-no-live-owner
             (caught-code #(run "restore-file" {:id (:id file)}))))
    (t/is (some? (deleted-at :file {:id (:id file)})))
    (t/is (some? (deleted-at :project {:id (:id project)})))
    (t/is (some? (deleted-at :team {:id (:id team)})))))

(t/deftest restore-project-refuses-team-without-live-owner
  (let [{:keys [profile team project]} (create-file 1)
        _     (delete-object :profile (:id profile))
        run   (as-superuser profile)]
    (t/is (= :team-has-no-live-owner
             (caught-code #(run "restore-project" {:id (:id project)}))))
    (t/is (some? (deleted-at :project {:id (:id project)})))
    (t/is (some? (deleted-at :team {:id (:id team)})))))

(t/deftest restore-team-refuses-without-live-owner
  (let [{:keys [profile team]} (create-file 1)
        _     (delete-object :profile (:id profile))
        run   (as-superuser profile)]
    (t/is (= :team-has-no-live-owner
             (caught-code #(run "restore-team" {:id (:id team)}))))
    (t/is (some? (deleted-at :team {:id (:id team)})))))

(t/deftest restore-profile-first-unblocks-team-restore
  ;; Restoring the owner profile first gives the team a live owner
  ;; back, so the team restore is allowed afterwards.
  (let [{:keys [profile team]} (create-file 1)
        _     (delete-object :profile (:id profile))
        run   (as-superuser profile)
        _     (run "restore-profile" {:id (:id profile)})
        _     (th/run-pending-jobs)
        out   (run "restore-team" {:id (:id team)})]
    (t/is (= (:id team) (:id out)))
    (t/is (nil? (deleted-at :team {:id (:id team)})))))
