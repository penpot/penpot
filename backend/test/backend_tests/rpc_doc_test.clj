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
   [app.config :as cf]
   [app.rpc :as-alias rpc]
   [app.rpc.doc :as rpc.doc]
   [backend-tests.helpers :as th]
   [clojure.test :as t]
   [yetti.response :as-alias yres]))

(t/use-fixtures :once th/state-init)

(t/deftest openapi-context-json-encode
  (smt/check!
   (smt/for [context (->> sg/int
                          (sg/fmap (fn [_]
                                     (#'rpc.doc/openapi-context (::rpc/methods th/*system*)))))]
     (try
       (json/encode context)
       true
       (catch Throwable _cause
         false)))
   {:num 15}))

(t/deftest doc-handler-returns-html-content-type
  (with-redefs [cf/flags #{:backend-api-doc}]
    (let [methods (::rpc/methods th/*system*)
          handler (#'rpc.doc/handler :methods methods
                                     :label "main"
                                     :entrypoint "http://localhost/api/main/methods"
                                     :openapi "http://localhost/api/main/doc/openapi"
                                     :template "app/templates/main-api-doc.tmpl")
          request {}
          response (handler request)]
      (t/is (= 200 (::yres/status response)))
      (t/is (= "text/html; charset=utf-8"
               (get-in response [::yres/headers "content-type"]))))))

