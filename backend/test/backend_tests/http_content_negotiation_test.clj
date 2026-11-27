;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.http-content-negotiation-test
  (:require
   [app.common.json :as json]
   [app.common.transit :as tr]
   [app.http.content-negotiation :as cnegot]
   [app.util.pointer-map :as pmap]
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

(t/deftest encoder-selection
  (let [message {:type :event :file-id #uuid "00000000-0000-0000-0000-000000000001"}]
    (doseq [format [:transit :unknown]]
      (t/is (= message (tr/decode-str ((cnegot/resolve-encoder format) message)))))
    (t/is (= {"type" "event" "fileId" (str (:file-id message))}
             (json/decode ((cnegot/resolve-encoder :json) message))))))

(t/deftest json-encodes-change-notifications
  (let [id       #uuid "00000000-0000-0000-0000-000000000001"
        fragment #uuid "00000000-0000-0000-0000-000000000002"
        instant  (java.time.Instant/parse "2026-01-02T03:04:05Z")
        change   {:type :mod-obj :page-id id
                  :objects {id {:fragment (pmap/create fragment {})}}}
        encode   (cnegot/resolve-encoder :json)]
    (doseq [type [:file-change :library-change]]
      (let [message {:type type :file-id id :revn 3
                     :modified-at instant :changes [change]}
            result  (json/decode (encode message))]
        (t/is (= (name type) (get result "type")))
        (t/is (= (str id) (get result "fileId")))
        (t/is (= (str instant) (get result "modifiedAt")))
        (t/is (= "mod-obj" (get-in result ["changes" 0 "type"])))
        (t/is (= (str id) (get-in result ["changes" 0 "pageId"])))
        (t/is (= [(str fragment) {}]
                 (get-in result ["changes" 0 "objects" (str id) "fragment"])))))))
