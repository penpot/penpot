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
   [app.rpc.climit :as-alias climit]
   [app.rpc.commands.binfile :as binfile]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [datoteka.fs :as fs]))

(t/use-fixtures :once th/state-init)
(t/use-fixtures :each th/database-reset)

(t/deftest import-binfile-schema-rejects-file-id
  ;; N1-06: file-id parameter must be removed from schema for security
  ;; The schema should not accept file-id as a valid parameter
  (let [schema @#'binfile/schema:import-binfile
        validator (sm/lazy-validator schema)

        ;; Valid params without file-id
        valid-params {:name "test"
                      :project-id (uuid/random)
                      :version 3
                      :upload-id (uuid/random)}

        ;; Params with file-id (should be rejected after fix)
        params-with-file-id (assoc valid-params :file-id (uuid/random))]

    ;; Valid params without file-id should pass
    (t/is (true? (validator valid-params))
          "params without file-id should be valid")

    ;; Params with file-id should fail validation after fix
    ;; (Currently this will fail because file-id is still in schema)
    (t/is (false? (validator params-with-file-id))
          "params with file-id should be rejected")))

(t/deftest import-binfile-has-concurrency-limit
  ;; N1-10: import-binfile must have a concurrency limit to prevent
  ;; connection pool exhaustion from concurrent imports
  (let [mdata (meta #'binfile/sm$import-binfile)]
    (t/is (some? (::climit/id mdata))
          "import-binfile must have ::climit/id metadata")))
