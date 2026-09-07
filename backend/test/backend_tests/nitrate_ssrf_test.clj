;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns backend-tests.nitrate-ssrf-test
  (:require
   [app.config :as cf]
   [app.http.client :as http]
   [app.nitrate :as nitrate]
   [app.setup :as-alias setup]
   [clojure.string :as str]
   [clojure.test :as t]
   [integrant.core :as ig]
   [java-http-clj.core :as jhttp]))

(def ^:private private-admin-uri "http://127.0.0.1:9090")

(defn- mk-cfg
  "Minimal nitrate cfg with a real HttpClient and nitrate client methods."
  []
  (let [http-client (jhttp/build-client {})
        base        {::http/client http-client
                     ::setup/shared-keys {:admin-console "test-shared-key"}}]
    (assoc base ::nitrate/client (ig/init-key ::nitrate/client base))))

(defn- with-admin-console-uri
  "Run `f` with :admin-console enabled and the given admin-console URI / allowlist."
  [admin-uri allowed-hosts f]
  (let [original-get cf/get]
    (with-redefs [cf/flags #{:admin-console}
                  cf/get (fn [key & args]
                           (case key
                             :admin-console-uri admin-uri
                             :ssrf-allowed-hosts allowed-hosts
                             (apply original-get key args)))]
      (f))))

(t/deftest nitrate-blocks-private-admin-console-uri
  (let [sent? (atom false)]
    (with-admin-console-uri
      private-admin-uri
      #{}
      (fn []
        (with-redefs [jhttp/send (fn [_req _opts]
                                   (reset! sent? true)
                                   {:status 200 :body "{\"licenses\":true}"})]
          (try
            (nitrate/call (mk-cfg) :connectivity {})
            (t/is false "should have raised :nitrate-unavailable")
            (catch Exception e
              (t/is (= :nitrate-unavailable (:type (ex-data e))))
              (t/is (false? @sent?)
                    "SSRF must stop the request before it reaches the network"))))))))

(t/deftest nitrate-proceeds-when-admin-console-host-allowlisted
  (let [captured (atom nil)]
    (with-admin-console-uri
      private-admin-uri
      #{"127.0.0.1"}
      (fn []
        (with-redefs [jhttp/send (fn [req _opts]
                                   (reset! captured req)
                                   {:status 200
                                    :body "{\"licenses\":true}"})]
          (let [result (nitrate/call (mk-cfg) :connectivity {})]
            (t/is (= {:licenses true} result))
            (t/is (some? @captured))
            (t/is (str/starts-with? (str (:uri @captured)) private-admin-uri))))))))
