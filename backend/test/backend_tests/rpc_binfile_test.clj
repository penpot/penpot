;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-binfile-test
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.v3 :as v3]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.binfile :as binfile]
   [app.storage.tmp :as tmp]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]
   [datoteka.io :as io]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest import-binfile-schema-omits-file-id
  ;; N1-06: file-id parameter must be removed from schema for security
  (let [schema @#'binfile/schema:import-binfile
        validator (sm/lazy-validator schema)

        valid-params {:name "test"
                      :project-id (uuid/random)
                      :version 3
                      :upload-id (uuid/random)}]

    (t/is (true? (validator valid-params))
          "params without file-id should be valid")

    (t/is (not (contains? (sm/keys (second schema)) :file-id))
          "file-id should not be a declared parameter")))

(t/deftest import-binfile-schema-rejects-unsupported-version
  ;; T1-N2-03: version parameter should be restricted to supported values (1 or 3)
  (let [schema @#'binfile/schema:import-binfile
        validator (sm/lazy-validator schema)
        base-params {:name "test"
                     :project-id (uuid/random)
                     :upload-id (uuid/random)}]

    ;; Version 1 should be accepted
    (t/is (true? (validator (assoc base-params :version 1)))
          "version 1 should be valid")

    ;; Version 3 should be accepted
    (t/is (true? (validator (assoc base-params :version 3)))
          "version 3 should be valid")

    ;; Version 2 should be rejected
    (t/is (false? (validator (assoc base-params :version 2)))
          "version 2 should be rejected")

    ;; Version 0 should be rejected
    (t/is (false? (validator (assoc base-params :version 0)))
          "version 0 should be rejected")

    ;; Negative version should be rejected
    (t/is (false? (validator (assoc base-params :version -1)))
          "negative version should be rejected")

    ;; Version 4 should be rejected
    (t/is (false? (validator (assoc base-params :version 4)))
          "version 4 should be rejected")))

(defn- export-file
  [profile]
  (let [file   (th/create-file* 1 {:profile-id (:id profile)
                                   :project-id (:default-project-id profile)
                                   :is-shared false})
        output (tmp/tempfile :suffix ".zip")]
    (v3/export-files!
     (-> th/*system*
         (assoc ::bfc/ids #{(:id file)})
         (assoc ::bfc/embed-assets false)
         (assoc ::bfc/include-libraries false))
     (io/output-stream output))
    output))

(defn- import-file
  [profile path]
  (let [out (th/command! {::th/type :import-binfile
                          ::rpc/profile-id (:id profile)
                          :project-id (:default-project-id profile)
                          :name "imported"
                          :file {:filename "imported.penpot"
                                 :size (fs/size path)
                                 :path path
                                 :mtype "application/zip"}})]
    (t/is (nil? (:error out)))
    (th/consume-sse (:result out))))

(defn- import-events
  [mock]
  (->> (:call-args-list @mock)
       (map second)
       (filter #(= "import-binfile" (:name %)))))

(t/deftest import-binfile-audit-event-records-file-ids
  (with-mocks [mock {:target 'app.loggers.audit/submit :return nil}]
    (let [profile  (th/create-profile* 1 {:is-active true})
          events   (import-file profile (export-file profile))
          file-ids (-> events last second)
          [event :as audit-events] (import-events mock)]

      (t/is (= :end (-> events last first)))
      (t/is (= 1 (count audit-events)))
      (t/is (= 1 (count file-ids)))
      (t/is (= (vec file-ids) (-> event :props :file-ids)))
      (t/is (= 1 (-> event :props :file-count)))
      (t/is (= "completed" (-> event :props :status)))
      (t/is (= (:default-project-id profile) (-> event :props :project-id)))
      (t/is (= "penpot" (-> event :props :referer)))
      (t/is (= (:id profile) (:profile-id event)))
      (t/is (= "command" (:type event)))
      (t/is (not (contains? (:props event) :file))))))

(t/deftest import-binfile-audit-event-records-failure
  (with-mocks [mock {:target 'app.loggers.audit/submit :return nil}]
    (let [profile (th/create-profile* 1 {:is-active true})
          path    (export-file profile)
          events  (with-redefs [v3/import-files!
                                (fn [_] (throw (ex-info "boom" {:type :internal})))]
                    (import-file profile path))
          [event :as audit-events] (import-events mock)]

      (t/is (= :error (-> events last first)))
      (t/is (= 1 (count audit-events)))
      (t/is (= "failed" (-> event :props :status)))
      (t/is (not (contains? (:props event) :file-ids))))))
