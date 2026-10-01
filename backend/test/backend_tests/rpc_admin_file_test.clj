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
   [app.binfile.v3 :as bf.v3]
   [app.common.data :as d]
   [app.common.time :as ct]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.features.file-snapshots :as fsnap]
   [app.http :as-alias http]
   [app.rpc :as rpc]
   [app.storage.tmp :as tmp]
   [app.util.services :as sv]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.io :as io]
   [yetti.response :as-alias yres]))

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

(defn- create-file
  [i]
  (let [profile (th/create-profile* i)
        team    (th/create-team* i {:profile-id (:id profile)})
        project (th/create-project* i {:profile-id (:id profile)
                                       :team-id (:id team)})
        file    (th/create-file* i {:profile-id (:id profile)
                                    :project-id (:id project)})]
    {:profile profile :file file}))

(defn- corrupt-file
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
  (let [{:keys [profile file]} (create-file 1)
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
  (let [{:keys [profile file]} (create-file 1)
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
  (let [{:keys [profile file]} (create-file 1)
        _     (corrupt-file (:id file))
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
  (let [{:keys [profile file]} (create-file 1)
        _     (corrupt-file (:id file))
        run   (as-superuser profile)
        out   (run "repair-file" {:file-id (:id file)})]
    (t/is (= (:id file) (:file-id out)))
    (t/is (= [] (:errors out)))
    (t/is (pos? (:changes out)))
    (t/is (true? (:snapshot-taken out)))
    (t/is (some #{"repair"} (snapshot-labels (:id file))))))

(t/deftest repair-without-snapshot-skips-it
  (let [{:keys [profile file]} (create-file 1)
        _     (corrupt-file (:id file))
        run   (as-superuser profile)
        out   (run "repair-file" {:file-id (:id file) :skip-snapshot true})]
    (t/is (= [] (:errors out)))
    (t/is (pos? (:changes out)))
    (t/is (false? (:snapshot-taken out)))
    (t/is (not (some #{"repair"} (snapshot-labels (:id file)))))))

(t/deftest repair-healthy-file-is-noop
  (let [{:keys [profile file]} (create-file 1)
        run   (as-superuser profile)
        out   (run "repair-file" {:file-id (:id file)})]
    (t/is (= [] (:errors out)))
    (t/is (= 0 (:changes out)))
    (t/is (false? (:snapshot-taken out)))))

;; ----------------------------------------------------------------
;; Listing
;; ----------------------------------------------------------------

(t/deftest list-returns-files-with-project-and-team
  (let [{:keys [profile file]} (create-file 1)
        run   (as-superuser profile)
        out   (run "get-files" {:id (:id file)})
        item  (first (:items out))]
    (t/is (= 1 (count (:items out))))
    (t/is (= (:id file) (:id item)))
    (t/is (= (:name file) (:name item)))
    (t/is (uuid? (:project-id item)))
    (t/is (string? (:project-name item)))
    (t/is (uuid? (:team-id item)))
    (t/is (string? (:team-name item)))
    (t/is (some? (:created-at item)))
    (t/is (some? (:modified-at item)))))

(t/deftest list-finds-by-exact-id
  (let [{:keys [profile file]} (create-file 1)
        _     (create-file 2)
        run   (as-superuser profile)
        out   (run "get-files" {:id (:id file)})]
    (t/is (= [(:id file)] (mapv :id (:items out))))))

(t/deftest list-finds-by-string-id
  (let [{:keys [profile file]} (create-file 1)
        run   (as-superuser profile)
        out   (run "get-files" {:id (str (:id file))})]
    (t/is (= [(:id file)] (mapv :id (:items out)))
          "the browser sends ids as strings")))

(t/deftest list-combined-id-and-deleted-filters
  (let [{:keys [profile file]} (create-file 1)
        _     (th/mark-file-deleted* {:id (:id file)})
        run   (as-superuser profile)]
    (let [out (run "get-files" {:id (:id file) :deleted false})]
      (t/is (= {:items []} out)
            "a deleted file never lists as live"))
    (let [team-id (:team-id (first (:items (run "get-files" {:id (:id file)}))))
          out     (run "get-files" {:team-id team-id :deleted true})]
      (t/is (= [(:id file)] (mapv :id (:items out)))
            "team scope composes with the deleted filter"))))

(t/deftest list-hides-files-under-deleted-projects
  (let [profile (th/create-profile* 1)
        team    (th/create-team* 1 {:profile-id (:id profile)})
        project (th/create-project* 1 {:profile-id (:id profile)
                                       :team-id (:id team)})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:id project)})
        run     (as-superuser profile)]
    (db/update! th/*system* :project {:deleted-at (ct/now)} {:id (:id project)})
    (let [out (run "get-files" {:project-id (:id project)})]
      (t/is (= {:items []} out)
            "the list hides children of deleted parents; get-file still resolves them"))))

(t/deftest list-filters-by-deleted
  (let [{:keys [profile file]} (create-file 1)
        _     (th/mark-file-deleted* {:id (:id file)})
        run   (as-superuser profile)]
    (let [out (run "get-files" {:deleted true})]
      (t/is (= [(:id file)] (mapv :id (:items out)))))
    (let [out (run "get-files" {:deleted false})]
      (t/is (not (contains? (set (map :id (:items out))) (:id file)))
            "the deleted file is excluded"))
    (let [out (run "get-files" {})]
      (t/is (= 1 (count (:items out)))
            "without the flag, deleted rows still list"))))

(t/deftest list-filters-by-team
  (let [{:keys [profile file]} (create-file 1)
        other (create-file 2)
        run   (as-superuser profile)
        team-id (:team-id (first (:items (run "get-files" {:id (:id file)}))))
        out   (run "get-files" {:team-id team-id})
        ids   (set (map :id (:items out)))]
    (t/is (contains? ids (:id file)))
    (t/is (not (contains? ids (:id (:file other)))))))

(t/deftest list-filters-by-project
  (let [{:keys [profile file]} (create-file 1)
        other (create-file 2)
        run   (as-superuser profile)
        project-id (:project-id (first (:items (run "get-files" {:id (:id file)}))))
        out   (run "get-files" {:project-id project-id})
        ids   (set (map :id (:items out)))]
    (t/is (contains? ids (:id file)))
    (t/is (not (contains? ids (:id (:file other)))))))

(t/deftest list-shows-deleted-files
  (let [{:keys [profile file]} (create-file 1)
        _     (th/mark-file-deleted* {:id (:id file)})
        run   (as-superuser profile)
        out   (run "get-files" {:id (:id file)})
        item  (first (:items out))]
    (t/is (= 1 (count (:items out))))
    (t/is (= (:id file) (:id item)))
    (t/is (some? (:deleted-at item)))))

(t/deftest list-paginates
  (let [{:keys [profile file]} (create-file 1)
        _     (create-file 2)
        run   (as-superuser profile)
        page1 (run "get-files" {:limit 1})]
    (t/is (= 1 (count (:items page1))))
    (t/is (some? (:next-since page1)))
    (t/is (some? (:next-id page1)))
    (let [page2 (run "get-files" {:limit 1
                                  :since (:next-since page1)
                                  :since-id (:next-id page1)})]
      (t/is (= 1 (count (:items page2))))
      (t/is (not= (-> page1 :items first :id)
                  (-> page2 :items first :id))))))

;; ----------------------------------------------------------------
;; Detail
;; ----------------------------------------------------------------

(t/deftest detail-unlisted-session-rejected
  (let [{:keys [profile file]} (create-file 1)
        run   (as-session #{} profile)]
    (t/is (= :superuser-required
             (caught-code #(run "get-file" {:id (:id file)}))))))

(t/deftest detail-resolves-regardless-of-deleted-state
  (let [profile (th/create-profile* 1)
        team    (th/create-team* 1 {:profile-id (:id profile)})
        project (th/create-project* 1 {:profile-id (:id profile)
                                       :team-id (:id team)})
        file    (th/create-file* 1 {:profile-id (:id profile)
                                    :project-id (:id project)})
        run     (as-superuser profile)]
    (let [out (run "get-file" {:id (:id file)})]
      (t/is (= (:id file) (:id out)))
      (t/is (= (:name file) (:name out)))
      (t/is (= (:id project) (:project-id out))))
    ;; A stray live file under a deleted project still resolves:
    ;; the admin must reach objects the list hides.
    (db/update! th/*system* :project {:deleted-at (ct/now)} {:id (:id project)})
    (let [out (run "get-file" {:id (:id file)})]
      (t/is (= (:id file) (:id out))))
    (th/mark-file-deleted* {:id (:id file)})
    (let [out (run "get-file" {:id (:id file)})]
      (t/is (some? (:deleted-at out))))))

(t/deftest detail-unknown-id-gives-not-found
  (let [{:keys [profile]} (create-file 1)
        run   (as-superuser profile)]
    (t/is (= :file-not-found
             (caught-code #(run "get-file" {:id (uuid/next)}))))))

;; ----------------------------------------------------------------
;; Transfer (export-files / import-files)
;; ----------------------------------------------------------------

(defn- run-response
  "Run a transfer command and invoke the returned response function,
  like the RPC dispatcher does."
  [run cmd params]
  ((run cmd params) {}))

(defn- body-size
  [response]
  (alength (.readAllBytes ^java.io.InputStream (::yres/body response))))

(t/deftest export-returns-download
  (let [{:keys [profile file]} (create-file 1)
        run      (as-superuser profile)
        response (run-response run "export-files" {:file-ids [(:id file)]})]
    (t/is (= 200 (::yres/status response)))
    (t/is (= "application/octet-stream"
             (get (::yres/headers response) "content-type")))
    (t/is (.endsWith (str (get (::yres/headers response) "content-disposition"))
                     ".penpot"))
    (t/is (pos? (body-size response)))))

(t/deftest export-without-ids-gives-params-validation
  (let [{:keys [profile]} (create-file 1)
        run (as-superuser profile)]
    (t/is (= :params-validation
             (caught-code #(run "export-files" {:file-ids []}))))))

(t/deftest export-unlisted-session-rejected
  (let [{:keys [file]} (create-file 1)
        profile (th/create-profile* 9)
        run     (as-session #{} profile)]
    (t/is (= :superuser-required
             (caught-code #(run "export-files" {:file-ids [(:id file)]}))))))

(t/deftest export-clone-creates-file-in-operator-project
  (let [{:keys [profile file]} (create-file 1)
        run        (as-superuser profile)
        project-id (:default-project-id profile)
        count-files (fn []
                      (:count (db/exec-one! th/*system*
                                            ["SELECT count(*) AS count FROM file WHERE project_id = ? AND deleted_at IS NULL"
                                             project-id])))
        before   (count-files)
        response (run-response run "export-files" {:file-ids [(:id file)]
                                                   :clone true})]
    (t/is (= 200 (::yres/status response)))
    (t/is (= "OK CLONED" (::yres/body response)))
    (t/is (= (inc before) (count-files)))))

(defn- export-to-tmp
  [file-id]
  (let [path (tmp/tempfile :prefix "penpot.import-test." :min-age "30m")]
    (with-open [output (io/output-stream path)]
      (-> th/*system*
          (assoc ::bfc/ids #{file-id})
          (assoc ::bfc/embed-assets false)
          (assoc ::bfc/include-libraries false)
          (bf.v3/export-files! output)))
    path))

(t/deftest export-anonymous-rejected
  (let [{:keys [file]} (create-file 1)]
    (t/is (= :authentication-required
             (caught-code #(call #{} nil nil #{} "export-files" {:file-ids [(:id file)]}))))))

(t/deftest import-anonymous-rejected
  (let [{:keys [file]} (create-file 1)
        path (export-to-tmp (:id file))]
    (t/is (= :authentication-required
             (caught-code #(call #{} nil nil #{} "import-files" {:file {:path path}}))))))

(t/deftest import-roundtrip-creates-file-in-operator-project
  (let [{:keys [profile file]} (create-file 1)
        run        (as-superuser profile)
        project-id (:default-project-id profile)
        count-files (fn []
                      (:count (db/exec-one! th/*system*
                                            ["SELECT count(*) AS count FROM file WHERE project_id = ? AND deleted_at IS NULL"
                                             project-id])))
        before   (count-files)
        path     (export-to-tmp (:id file))
        response (run-response run "import-files" {:file {:path path
                                                          :filename "roundtrip.penpot"}})]
    (t/is (= 200 (::yres/status response)))
    (t/is (= "OK" (::yres/body response)))
    (t/is (= (inc before) (count-files)))))

(t/deftest import-without-file-gives-params-validation
  (let [{:keys [profile]} (create-file 1)
        run (as-superuser profile)]
    (t/is (= :params-validation
             (caught-code #(run "import-files" {}))))))

(t/deftest import-unlisted-session-rejected
  (let [{:keys [file]} (create-file 1)
        profile (th/create-profile* 9)
        run     (as-session #{} profile)
        path    (export-to-tmp (:id file))]
    (t/is (= :superuser-required
             (caught-code #(run "import-files" {:file {:path path}}))))))
