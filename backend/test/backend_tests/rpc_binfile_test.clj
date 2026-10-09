;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.rpc-binfile-test
  (:require
   [app.binfile.v3 :as bf.v3]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.binfile :as binfile]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]
   [mockery.core :refer [with-mocks]]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest import-binfile-schema-omits-file-id
  ;; N1-06: file-id parameter must be removed from schema for security
  (let [schema @#'binfile/schema:import-binfile
        validator (sm/lazy-validator schema)

        valid-params
        {:name "test"
         :project-id (uuid/random)
         :version 3
         :upload-id (uuid/random)}

        params-with-file-id
        (assoc valid-params :file-id (uuid/random))]

    (t/is (true? (validator valid-params))
          "params without file-id should be valid")

    (t/is (not (contains? (sm/keys (second schema)) :file-id))
          "file-id should not be a declared parameter")

    ;; Params with file-id should fail (schema closed)
    (t/is (false? (validator params-with-file-id))
          "params with file-id should be rejected")))

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

(t/deftest import-binfile-audit-events-carry-entity-ids
  ;; The imported files are created inside the importer, so the command emits a
  ;; create-file event per file for a local projection.
  (with-mocks [audit-mock {:target 'app.loggers.audit/submit :return nil}]
    (with-redefs [cf/flags (conj cf/flags :audit-log)]
      (let [profile (th/create-profile* 1 {:is-active true})
            team    (th/create-team* 51 {:profile-id (:id profile)})
            project (th/create-project* 51 {:profile-id (:id profile)
                                            :team-id (:id team)})
            file-id (uuid/next)]
        (with-redefs [bf.v3/import-files! (fn [_cfg] {:file-ids [file-id]})]
          (#'binfile/import-binfile
           th/*system*
           {::rpc/profile-id (:id profile)
            :profile-id (:id profile)
            :project-id (:id project)
            :version 3
            :name "imported"
            :file {:filename "package.penpot"
                   :path (th/tempfile "backend_tests/test_files/svg-attrs-camel-case.penpot")
                   :mtype "application/zip"
                   :size 1}}))

        (let [events (->> (:call-args-list @audit-mock)
                          (map second)
                          (filter #(= "create-file" (:name %))))]
          (t/is (= 1 (count events)))
          (t/is (= file-id (get-in (first events) [:props :id])))
          (t/is (= (:id project) (get-in (first events) [:props :project-id])))
          (t/is (= (:id team) (get-in (first events) [:props :team-id]))))))))
