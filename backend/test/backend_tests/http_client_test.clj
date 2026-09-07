;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.http-client-test
  (:require
   [app.http.client :as http]
   [clojure.test :as t]
   [java-http-clj.core :as jhttp]))

(defn- capture-send!
  "Run `f` with java-http-clj send stubbed; return the request map passed to send."
  [f]
  (let [captured (atom nil)]
    (with-redefs [jhttp/send (fn [req _opts]
                               (reset! captured req)
                               {:status 200 :body ""})]
      (f)
      @captured)))

(t/deftest send-injects-default-timeout-when-absent
  (let [client  (jhttp/build-client {})
        request (capture-send!
                 (fn []
                   (http/send! client {:method :get :uri "https://example.com/"})))]
    (t/is (= http/default-request-timeout (:timeout request)))))

(t/deftest send-preserves-caller-supplied-timeout
  (let [client  (jhttp/build-client {})
        request (capture-send!
                 (fn []
                   (http/send! client {:method :get
                                       :uri "https://example.com/"
                                       :timeout 5000})))]
    (t/is (= 5000 (:timeout request)))))
