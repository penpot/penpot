;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-admin-file-test
  "Tests for the superuser-guarded file validate/repair commands."
  (:require
   [app.auth :as-alias auth]
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.file-snapshots :as fsnap]
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
        (sv/scan-ns 'app.rpc.admin.file)))

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
    {:profile profile :file file}))

(defn- corrupt-file!
  "Hang one shape off a nonexistent parent so validation reports
  errors. Persists straight through the binfile write path, which
  performs no referential checks."
  [file-id]
  (db/tx-run! th/*system*
              (fn [cfg]
                (let [file    (bfc/get-file cfg file-id :realize? true)
                      page-id (first (keys (-> file :data :pages-index)))
                      orphan  (cts/setup-shape {:id (uuid/next)
                                                :name "orphan"
                                                :type :rect
                                                :parent-id (uuid/next)
                                                :frame-id uuid/zero})]
                  (bfc/update-file! cfg (assoc-in file [:data :pages-index page-id :objects (:id orphan)]
                                                  orphan))))))

(defn- snapshot-labels
  [file-id]
  (->> (fsnap/get-visible-snapshots th/*system* file-id)
       (mapv :label)))

;; ----------------------------------------------------------------
;; Guard
;; ----------------------------------------------------------------

(t/deftest unlisted-session-rejected
  (let [profile (th/create-profile* 1)
        run     (as-session #{} profile)]
    (t/is (= :superuser-required
             (caught-code #(run "get-files" {}))))
    (t/is (= :superuser-required
             (caught-code #(run "validate-file" {:file-id (uuid/next)}))))
    (t/is (= :superuser-required
             (caught-code #(run "repair-file" {:file-id (uuid/next)}))))))

(t/deftest unlisted-token-without-scope-rejected
  (let [profile (th/create-profile* 1)]
    (t/is (= :superuser-required
             (caught-code #(call #{} :token (:id profile) #{}
                                 "validate-file" {:file-id (uuid/next)}))))))

(t/deftest token-with-granted-superuser-passes
  (let [{:keys [profile file]} (create-file! 1)
        out (call #{} :token (:id profile) #{"superuser"}
                  "validate-file" {:file-id (:id file)})]
    (t/is (= (:id file) (:file-id out)))
    (t/is (= [] (:errors out)))))

(t/deftest anonymous-rejected
  (t/is (= :authentication-required
           (caught-code #(call #{} nil nil #{}
                               "validate-file" {:file-id (uuid/next)})))))

;; ----------------------------------------------------------------
;; Validate
;; ----------------------------------------------------------------

(t/deftest validate-healthy-file-returns-no-errors
  (let [{:keys [profile file]} (create-file! 1)
        run   (as-superuser profile)
        out   (run "validate-file" {:file-id (:id file)})]
    (t/is (= (:id file) (:file-id out)))
    (t/is (= [] (:errors out)))))

(t/deftest validate-unknown-id-gives-not-found
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)]
    (t/is (= :file-not-found
             (caught-code #(run "validate-file" {:file-id (uuid/next)}))))))

(t/deftest validate-corrupt-file-returns-scalar-errors
  (let [{:keys [profile file]} (create-file! 1)
        _     (corrupt-file! (:id file))
        run   (as-superuser profile)
        out   (run "validate-file" {:file-id (:id file)})
        codes (set (map :code (:errors out)))]
    (t/is (seq (:errors out)))
    (t/is (contains? codes "parent-not-found"))
    (doseq [error (:errors out)]
      (t/is (string? (:code error)))
      (t/is (string? (:hint error)))
      (t/is (= (:id file) (:file-id error)))
      (t/is (not (contains? error :shape)))
      (t/is (not (contains? error :args))))))

;; ----------------------------------------------------------------
;; Repair
;; ----------------------------------------------------------------

(t/deftest repair-unknown-id-gives-not-found
  (let [profile (th/create-profile* 1)
        run     (as-superuser profile)]
    (t/is (= :file-not-found
             (caught-code #(run "repair-file" {:file-id (uuid/next)}))))))

(t/deftest repair-corrupt-file-clears-errors-and-snapshots
  (let [{:keys [profile file]} (create-file! 1)
        _     (corrupt-file! (:id file))
        run   (as-superuser profile)
        out   (run "repair-file" {:file-id (:id file)})]
    (t/is (= (:id file) (:file-id out)))
    (t/is (= [] (:errors out)))
    (t/is (pos? (:changes out)))
    (t/is (true? (:snapshot-taken out)))
    (t/is (some #{"repair"} (snapshot-labels (:id file))))))

(t/deftest repair-without-snapshot-skips-it
  (let [{:keys [profile file]} (create-file! 1)
        _     (corrupt-file! (:id file))
        run   (as-superuser profile)
        out   (run "repair-file" {:file-id (:id file) :skip-snapshot true})]
    (t/is (= [] (:errors out)))
    (t/is (pos? (:changes out)))
    (t/is (false? (:snapshot-taken out)))
    (t/is (not (some #{"repair"} (snapshot-labels (:id file)))))))

(t/deftest repair-healthy-file-is-noop
  (let [{:keys [profile file]} (create-file! 1)
        run   (as-superuser profile)
        out   (run "repair-file" {:file-id (:id file)})]
    (t/is (= [] (:errors out)))
    (t/is (= 0 (:changes out)))
    (t/is (false? (:snapshot-taken out)))))

;; ----------------------------------------------------------------
;; Listing
;; ----------------------------------------------------------------

(t/deftest list-returns-files-with-project-and-team
  (let [{:keys [profile file]} (create-file! 1)
        run   (as-superuser profile)
        out   (run "get-files" {:search (:name file)})
        item  (first (:items out))]
    (t/is (= 1 (count (:items out))))
    (t/is (= (:id file) (:id item)))
    (t/is (= (:name file) (:name item)))
    (t/is (uuid? (:project-id item)))
    (t/is (string? (:project-name item)))
    (t/is (uuid? (:team-id item)))
    (t/is (string? (:team-name item)))
    (t/is (some? (:modified-at item)))))

(t/deftest list-finds-by-exact-id
  (let [{:keys [profile file]} (create-file! 1)
        _     (create-file! 2)
        run   (as-superuser profile)
        out   (run "get-files" {:search (str (:id file))})]
    (t/is (= [(:id file)] (mapv :id (:items out))))))

(t/deftest list-filters-by-team
  (let [{:keys [profile file]} (create-file! 1)
        other (create-file! 2)
        run   (as-superuser profile)
        team-id (:team-id (first (:items (run "get-files" {:search (:name file)}))))
        out   (run "get-files" {:team-id team-id})
        ids   (set (map :id (:items out)))]
    (t/is (contains? ids (:id file)))
    (t/is (not (contains? ids (:id (:file other)))))))

(t/deftest list-filters-by-project
  (let [{:keys [profile file]} (create-file! 1)
        other (create-file! 2)
        run   (as-superuser profile)
        project-id (:project-id (first (:items (run "get-files" {:search (:name file)}))))
        out   (run "get-files" {:project-id project-id})
        ids   (set (map :id (:items out)))]
    (t/is (contains? ids (:id file)))
    (t/is (not (contains? ids (:id (:file other)))))))

(t/deftest list-hides-deleted-files
  (let [{:keys [profile file]} (create-file! 1)
        _     (th/mark-file-deleted* {:id (:id file)})
        run   (as-superuser profile)
        out   (run "get-files" {:search (:name file)})]
    (t/is (= [] (:items out)))))

(t/deftest list-search-and-pagination
  (let [{:keys [profile file]} (create-file! 1)
        _     (create-file! 2)
        run   (as-superuser profile)
        page1 (run "get-files" {:search "file" :limit 1})]
    (t/is (= 1 (count (:items page1))))
    (t/is (some? (:next-since page1)))
    (t/is (some? (:next-id page1)))
    (let [page2 (run "get-files" {:search "file" :limit 1
                                  :since (:next-since page1)
                                  :since-id (:next-id page1)})]
      (t/is (= 1 (count (:items page2))))
      (t/is (not= (-> page1 :items first :id)
                  (-> page2 :items first :id))))))
