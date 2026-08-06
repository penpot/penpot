;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.rpc-binfile-test
  (:require
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.binfile :as binfile]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]))

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
