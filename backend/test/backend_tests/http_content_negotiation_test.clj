;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.http-content-negotiation-test
  (:require
   [app.http.content-negotiation :as cnegot]
   [clojure.test :as t]))

(t/deftest negotiate-format-defaults-to-transit
  (t/is (= :transit (cnegot/negotiate-format {})))
  (t/is (= :transit (cnegot/negotiate-format {:headers {"accept" "*/*"}})))
  (t/is (= :transit (cnegot/negotiate-format {:headers {"accept" "text/event-stream"}}))))

(t/deftest negotiate-format-from-accept-header
  (t/is (= :transit (cnegot/negotiate-format
                     {:headers {"accept" "application/transit+json"}})))
  (t/is (= :transit (cnegot/negotiate-format
                     {:headers {"accept" "application/transit+json,text/event-stream,*/*"}})))
  (t/is (= :json (cnegot/negotiate-format
                  {:headers {"accept" "application/json"}})))
  (t/is (= :json (cnegot/negotiate-format
                  {:headers {"accept" "application/json, text/event-stream"}}))))

(t/deftest negotiate-format-from-query-param
  (t/is (= :json (cnegot/negotiate-format {:query-params {:_fmt "json"}})))
  (t/is (= :json (cnegot/negotiate-format
                  {:query-params {:_fmt "json"}
                   :headers {"accept" "application/transit+json"}}))))

(t/deftest negotiate-format-only-json-param-is-recognized
  (t/is (= :json (cnegot/negotiate-format
                  {:query-params {:_fmt "transit"}
                   :headers {"accept" "application/json"}})))
  (t/is (= :transit (cnegot/negotiate-format {:query-params {:_fmt "transit"}}))))

(t/deftest negotiate-format-without-accept-header
  (t/is (= :transit (cnegot/negotiate-format {:headers {}}))))
