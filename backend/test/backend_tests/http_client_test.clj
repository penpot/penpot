;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.http-client-test
  (:require
   [app.http.client :as http]
   [clojure.test :as t]
   [java-http-clj.core :as jhttp]
   [mockery.core :refer [with-mocks]]))

(t/deftest send-injects-default-timeout-when-absent
  (with-mocks [mock {:target 'java-http-clj.core/send
                     :return {:status 200 :body ""}}]
    (let [client (jhttp/build-client {})]
      (http/send! client {:method :get :uri "https://example.com/"})
      (let [[req _opts] (:call-args @mock)]
        (t/is (= http/default-request-timeout (:timeout req)))))))

(t/deftest send-preserves-caller-supplied-timeout
  (with-mocks [mock {:target 'java-http-clj.core/send
                     :return {:status 200 :body ""}}]
    (let [client (jhttp/build-client {})]
      (http/send! client {:method :get
                          :uri "https://example.com/"
                          :timeout 5000})
      (let [[req _opts] (:call-args @mock)]
        (t/is (= 5000 (:timeout req)))))))