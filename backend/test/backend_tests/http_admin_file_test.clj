;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.http-admin-file-test
  "Tests for the `/api/admin` file transfer routes."
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v3 :as bf.v3]
   [app.common.uuid :as uuid]
   [app.db :as db]
   [app.http.access-token :as-alias actoken]
   [app.http.admin :as admin]
   [app.http.session :as-alias session]
   [app.storage.tmp :as tmp]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.io :as io]
   [yetti.response :as-alias yres]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

;; ----------------------------------------------------------------
;; Helpers
;; ----------------------------------------------------------------

(defn- create-file!
  [i]
  (let [profile (th/create-profile* i)
        team    (th/create-team* i {:profile-id (:id profile)})
        project (th/create-project* i {:profile-id (:id profile)
                                       :team-id (:id team)})
        file    (th/create-file* i {:profile-id (:id profile)
                                    :project-id (:id project)})]
    {:profile profile :file file}))

(defn- run-export
  "Call the export handler directly, as the route would after the
  session/token middleware has resolved the caller."
  [cfg request]
  (admin/file-export-handler cfg request))

(defn- as-superuser
  [profile]
  {:cfg     (assoc th/*system* :app.auth/superusers #{(:id profile)})
   :request {::session/profile-id (:id profile)}})

(defn- caught-code
  [thunk]
  (try
    (thunk)
    ::no-throw
    (catch clojure.lang.ExceptionInfo cause
      (th/ex-code cause))))

(defn- body-size
  [response]
  (alength (.readAllBytes ^java.io.InputStream (::yres/body response))))

;; ----------------------------------------------------------------
;; Gate
;; ----------------------------------------------------------------

(t/deftest anonymous-rejected
  (let [{:keys [file]} (create-file! 1)
        cfg     (assoc th/*system* :app.auth/superusers #{})
        request {:params {:file-ids [(str (:id file))]}}]
    (t/is (= :authentication-required
             (caught-code #(run-export cfg request))))))

(t/deftest unlisted-session-rejected
  (let [{:keys [file]} (create-file! 1)
        profile (th/create-profile* 9)
        cfg     (assoc th/*system* :app.auth/superusers #{})
        request {:params {:file-ids [(str (:id file))]}
                 ::session/profile-id (:id profile)}]
    (t/is (= :superuser-required
             (caught-code #(run-export cfg request))))))

(t/deftest token-with-granted-superuser-passes
  (let [{:keys [profile file]} (create-file! 1)
        cfg     (assoc th/*system* :app.auth/superusers #{})
        request {:params {:file-ids [(str (:id file))]}
                 ::actoken/profile-id (:id profile)
                 ::actoken/perms #{"superuser"}}
        response (run-export cfg request)]
    (t/is (= 200 (::yres/status response)))
    (t/is (pos? (body-size response)))))

;; ----------------------------------------------------------------
;; Export
;; ----------------------------------------------------------------

(t/deftest export-returns-download
  (let [{:keys [profile file]} (create-file! 1)
        {:keys [cfg request]} (as-superuser profile)
        response (run-export cfg (assoc request
                                        :params {:file-ids [(str (:id file))]}))]
    (t/is (= 200 (::yres/status response)))
    (t/is (= "application/octet-stream"
             (get (::yres/headers response) "content-type")))
    (t/is (pos? (body-size response)))))

(t/deftest export-accepts-comma-separated-ids
  (let [{:keys [profile file]} (create-file! 1)
        other   (create-file! 2)
        {:keys [cfg request]} (as-superuser profile)
        response (run-export cfg (assoc request
                                        :params {:file-ids (str (:id file) "," (:id (:file other)))}))]
    (t/is (= 200 (::yres/status response)))
    (t/is (pos? (body-size response)))))

(t/deftest export-without-ids-gives-missing-arguments
  (let [{:keys [profile]} (create-file! 1)
        {:keys [cfg request]} (as-superuser profile)]
    (t/is (= :missing-arguments
             (caught-code #(run-export cfg (assoc request :params {})))))))

(t/deftest export-clone-creates-file-in-operator-project
  (let [{:keys [profile file]} (create-file! 1)
        {:keys [cfg request]} (as-superuser profile)
        project-id (:default-project-id profile)
        count-files (fn []
                      (:count (db/exec-one! th/*system*
                                            ["SELECT count(*) AS count FROM file WHERE project_id = ? AND deleted_at IS NULL"
                                             project-id])))
        before   (count-files)
        response (run-export cfg (assoc request
                                        :params {:file-ids [(str (:id file))]
                                                 :clone "true"}))]
    (t/is (= 200 (::yres/status response)))
    (t/is (= "OK CLONED" (::yres/body response)))
    (t/is (= (inc before) (count-files)))))

;; ----------------------------------------------------------------
;; Import
;; ----------------------------------------------------------------

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

(defn- run-import
  [cfg request]
  (admin/file-import-handler cfg request))

(t/deftest import-roundtrip-creates-file-in-operator-project
  (let [{:keys [profile file]} (create-file! 1)
        {:keys [cfg request]} (as-superuser profile)
        project-id (:default-project-id profile)
        count-files (fn []
                      (:count (db/exec-one! th/*system*
                                            ["SELECT count(*) AS count FROM file WHERE project_id = ? AND deleted_at IS NULL"
                                             project-id])))
        before   (count-files)
        path     (export-to-tmp (:id file))
        response (run-import cfg (assoc request
                                        :params {:file {:path path}}))]
    (t/is (= 200 (::yres/status response)))
    (t/is (= "OK" (::yres/body response)))
    (t/is (= (inc before) (count-files)))))

(t/deftest import-without-file-gives-missing-upload-file
  (let [{:keys [profile]} (create-file! 1)
        {:keys [cfg request]} (as-superuser profile)]
    (t/is (= :missing-upload-file
             (caught-code #(run-import cfg (assoc request :params {})))))))

(t/deftest import-unlisted-session-rejected
  (let [{:keys [file]} (create-file! 1)
        profile (th/create-profile* 9)
        cfg     (assoc th/*system* :app.auth/superusers #{})
        path    (export-to-tmp (:id file))
        request {:params {:file {:path path}}
                 ::session/profile-id (:id profile)}]
    (t/is (= :superuser-required
             (caught-code #(run-import cfg request))))))
