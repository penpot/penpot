;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.plugins.location-test
  (:require
   [app.common.data.macros :as dm]
   [app.common.uri :as u]
   [app.config :as cf]
   [app.plugins.location :as location]
   [cljs.test :as t :include-macros true]))

(defn- state-with-query
  [query-params]
  {:route {:data {:name (keyword (:screen query-params))}
           :params {:path {} :query query-params}
           :query-params query-params}})

(defn- href-params
  [href]
  (-> (u/uri href) :query u/query-string->map))

(t/deftest base-url-is-the-public-uri
  (let [result (location/location (state-with-query {:screen "dashboard-recent"}))]
    (t/is (= (dm/str cf/public-uri) (:baseUrl result)))))

(t/deftest workspace-params-are-exposed
  (let [query  {:screen  "workspace"
                :team-id "t1"
                :file-id "f1"
                :page-id "p1"
                :layout  "layers"}
        result (location/location (state-with-query query))]
    (t/is (= {"screen"  "workspace"
              "team-id" "t1"
              "file-id" "f1"
              "page-id" "p1"
              "layout"  "layers"}
             (:params result)))
    (t/is (= query (href-params (:href result))))
    (t/is (= (dm/str cf/public-uri) (subs (:href result) 0 (count (dm/str cf/public-uri)))))))

(t/deftest custom-params-are-exposed
  (let [result (location/location (state-with-query {:screen "workspace" :my-plugin "on"}))]
    (t/is (= "on" (get-in result [:params "my-plugin"])))))

(t/deftest secret-params-are-left-out
  (let [query  {:screen           "workspace"
                :file-id          "f1"
                :token            "s1"
                :invitation-token "s2"
                :share-id         "s3"
                :request          "s4"
                :code             "s5"
                :state            "s6"
                :client-secret    "s7"
                :password         "s8"}
        result (location/location (state-with-query query))]
    (t/is (= {"screen" "workspace" "file-id" "f1"} (:params result)))
    (t/is (= {:screen "workspace" :file-id "f1"} (href-params (:href result))))
    (t/is (not (re-find #"s\d" (:href result))))))

(t/deftest repeated-params-keep-the-last-value
  (let [result (location/location (state-with-query {:screen "workspace" :page-id ["p1" "p2"]}))]
    (t/is (= "p2" (get-in result [:params "page-id"])))
    (t/is (= "p2" (:page-id (href-params (:href result)))))))

(t/deftest no-route-returns-the-base-url
  (let [result (location/location {})]
    (t/is (= {} (:params result)))
    (t/is (= (:baseUrl result) (:href result)))))
