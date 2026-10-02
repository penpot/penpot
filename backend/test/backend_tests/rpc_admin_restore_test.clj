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

(defn- create-file!
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

(defn- delete!
  "Delete through the shared cascade, exercising the same path as
  the worker."
  [object id]
  (db/tx-run! th/*system* cascade/run-cascade object id {:deleted-at (ct/now)}))

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
;; File
;; ----------------------------------------------------------------

(t/deftest restore-file-single-restores-row-only
  (let [{:keys [profile file]} (create-file! 1)
        _     (delete! :file (:id file))
        run   (as-superuser profile)]
    (t/is (some? (deleted-at :file {:id (:id file)})))
    (t/is (some? (deleted-at :file-data {:file-id (:id file)})))
    (let [out (run "restore-file" {:id (:id file)})]
      (t/is (= (:id file) (:id out)))
      (t/is (false? (:recursive out))))
    (t/is (nil? (deleted-at :file {:id (:id file)})))
    (t/is (some? (deleted-at :file-data {:file-id (:id file)})))))

(t/deftest restore-file-recursive-restores-rows
  (let [{:keys [profile file]} (create-file! 1)
        _     (delete! :file (:id file))
        run   (as-superuser profile)
        out   (run "restore-file" {:id (:id file) :recursive true})]
    (t/is (= (:id file) (:id out)))
    (t/is (true? (:recursive out)))
    (t/is (nil? (deleted-at :file {:id (:id file)})))
    (t/is (nil? (deleted-at :file-data {:file-id (:id file)})))))

;; ----------------------------------------------------------------
;; Project
;; ----------------------------------------------------------------

(t/deftest restore-project-single-restores-row-only
  (let [{:keys [profile project file]} (create-file! 1)
        _     (delete! :project (:id project))
        run   (as-superuser profile)]
    (t/is (some? (deleted-at :project {:id (:id project)})))
    (t/is (some? (deleted-at :file {:id (:id file)})))
    (let [out (run "restore-project" {:id (:id project)})]
      (t/is (= (:id project) (:id out)))
      (t/is (false? (:recursive out))))
    (t/is (nil? (deleted-at :project {:id (:id project)})))
    (t/is (some? (deleted-at :file {:id (:id file)})))))

(t/deftest restore-project-recursive-restores-files
  (let [{:keys [profile project file]} (create-file! 1)
        _     (delete! :project (:id project))
        run   (as-superuser profile)
        out   (run "restore-project" {:id (:id project) :recursive true})]
    (t/is (= (:id project) (:id out)))
    (t/is (true? (:recursive out)))
    (t/is (nil? (deleted-at :project {:id (:id project)})))
    (t/is (nil? (deleted-at :file {:id (:id file)})))
    (t/is (nil? (deleted-at :file-data {:file-id (:id file)})))))

;; ----------------------------------------------------------------
;; Team
;; ----------------------------------------------------------------

(t/deftest restore-team-single-restores-row-only
  (let [{:keys [profile team project file]} (create-file! 1)
        _     (delete! :team (:id team))
        run   (as-superuser profile)]
    (t/is (some? (deleted-at :team {:id (:id team)})))
    (t/is (some? (deleted-at :project {:id (:id project)})))
    (let [out (run "restore-team" {:id (:id team)})]
      (t/is (= (:id team) (:id out)))
      (t/is (false? (:recursive out))))
    (t/is (nil? (deleted-at :team {:id (:id team)})))
    (t/is (some? (deleted-at :project {:id (:id project)})))
    (t/is (some? (deleted-at :file {:id (:id file)})))))

(t/deftest restore-team-recursive-restores-cascade
  (let [{:keys [profile team project file]} (create-file! 1)
        _     (delete! :team (:id team))
        run   (as-superuser profile)
        out   (run "restore-team" {:id (:id team) :recursive true})]
    (t/is (= (:id team) (:id out)))
    (t/is (true? (:recursive out)))
    (t/is (nil? (deleted-at :team {:id (:id team)})))
    (t/is (nil? (deleted-at :project {:id (:id project)})))
    (t/is (nil? (deleted-at :file {:id (:id file)})))
    (t/is (nil? (deleted-at :file-data {:file-id (:id file)})))))

;; ----------------------------------------------------------------
;; Profile
;; ----------------------------------------------------------------

(t/deftest restore-profile-single-restores-row-only
  (let [profile (th/create-profile* 1)
        team    (th/create-team* 1 {:profile-id (:id profile)})
        _       (delete! :profile (:id profile))
        run     (as-superuser profile)]
    (t/is (some? (deleted-at :profile {:id (:id profile)})))
    (t/is (some? (deleted-at :team {:id (:id team)})))
    (let [out (run "restore-profile" {:id (:id profile)})]
      (t/is (= (:id profile) (:id out)))
      (t/is (false? (:recursive out))))
    (t/is (nil? (deleted-at :profile {:id (:id profile)})))
    (t/is (some? (deleted-at :team {:id (:id team)})))))

(t/deftest restore-profile-recursive-restores-teams
  (let [profile (th/create-profile* 1)
        team    (th/create-team* 1 {:profile-id (:id profile)})
        _       (delete! :profile (:id profile))
        run     (as-superuser profile)
        out     (run "restore-profile" {:id (:id profile) :recursive true})]
    (t/is (= (:id profile) (:id out)))
    (t/is (true? (:recursive out)))
    (t/is (nil? (deleted-at :profile {:id (:id profile)})))
    (t/is (nil? (deleted-at :team {:id (:id team)})))))
