;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns backend-tests.rpc-doc-test
  "Internal binfile test, no RPC involved"
  (:require
   [app.common.json :as json]
   [app.common.pprint :as pp]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.schema.test :as smt]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as rpc.doc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]))

(t/use-fixtures :once th/state-init)

(t/deftest openapi-context-json-encode
  (smt/check!
   (smt/for [context (->> sg/int
                          (sg/fmap (fn [_]
                                     (rpc.doc/prepare-openapi-context (::rpc/methods th/*system*)))))]
     (try
       (json/encode context)
       true
       (catch Throwable _cause
         false)))
   {:num 30}))




