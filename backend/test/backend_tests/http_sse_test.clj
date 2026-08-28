;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.http-sse-test
  (:require
   [app.common.transit :as tr]
   [app.http.sse :as sse]
   [app.util.events :as events]
   [clojure.string :as str]
   [clojure.test :as t]
   [yetti.response :as yres])
  (:import
   java.io.ByteArrayOutputStream))

(def ^:private test-file-id #uuid "00000000-0000-0000-0000-000000000001")

(def ^:private progress-json
  (str "{\"fileId\":\"" test-file-id "\",\"index\":1,\"total\":2}"))

(defn- make-handler
  []
  (fn []
    (events/tap :progress {:file-id test-file-id :index 1 :total 2})
    {:status "ok"}))

(defn- run-sse
  "Runs the sse response with the provided fake request and returns
  the raw stream content as a string."
  [request]
  (let [response ((sse/response (make-handler)) request)
        output   (ByteArrayOutputStream.)]
    (yres/write-body-to-stream (::yres/body response) response output)
    (.toString output "UTF-8")))

(defn- data-events
  [stream]
  (->> (str/split stream #"\n\n")
       (keep (fn [block]
               (let [[_ event] (re-find #"event: (.+)" block)
                     [_ data]  (re-find #"data: (.+)" block)]
                 (when (and event data)
                   {:event (str/trim event) :data (str/trim data)}))))
       (vec)))

(t/deftest default-format-is-transit
  (let [[progress end] (data-events (run-sse {}))]
    (t/is (= "progress" (:event progress)))
    (t/is (= {:file-id test-file-id :index 1 :total 2}
             (tr/decode-str (:data progress))))
    (t/is (= "end" (:event end)))
    (t/is (= {:status "ok"} (tr/decode-str (:data end))))))

(t/deftest accept-transit-header-uses-transit
  (let [[progress] (data-events (run-sse {:headers {"accept" "application/transit+json"}}))]
    (t/is (= {:file-id test-file-id :index 1 :total 2}
             (tr/decode-str (:data progress))))))

(t/deftest accept-frontend-combo-header-uses-transit
  (let [[progress] (data-events (run-sse {:headers {"accept" "application/transit+json,text/event-stream,*/*"}}))]
    (t/is (= {:file-id test-file-id :index 1 :total 2}
             (tr/decode-str (:data progress))))))

(t/deftest accept-json-header-uses-json
  (let [stream (run-sse {:headers {"accept" "application/json"}})
        [progress end] (data-events stream)]
    (t/is (= "progress" (:event progress)))
    (t/is (= progress-json (:data progress)))
    (t/is (= "end" (:event end)))
    (t/is (= "{\"status\":\"ok\"}" (:data end)))))

(t/deftest fmt-json-query-param-uses-json
  (let [[progress] (data-events (run-sse {:query-params {:_fmt "json"}}))]
    (t/is (= progress-json (:data progress)))))

(t/deftest fmt-json-query-param-precedence-over-accept
  (let [[progress] (data-events (run-sse {:query-params {:_fmt "json"}
                                          :headers {"accept" "application/transit+json"}}))]
    (t/is (= progress-json (:data progress)))))

(t/deftest wildcard-accept-defaults-to-transit
  (let [[progress] (data-events (run-sse {:headers {"accept" "*/*"}}))]
    (t/is (= {:file-id test-file-id :index 1 :total 2}
             (tr/decode-str (:data progress))))))

(t/deftest error-events-are-negotiated
  (let [response ((sse/response (fn []
                                  (throw (ex-info "boom"
                                                  {:type :validation
                                                   :code :generic
                                                   :hint "boom"}))))
                  {:headers {"accept" "application/json"}})
        output   (ByteArrayOutputStream.)]
    (yres/write-body-to-stream (::yres/body response) response output)
    (let [[error] (data-events (.toString output "UTF-8"))]
      (t/is (= "error" (:event error)))
      (t/is (= "{\"type\":\"validation\",\"code\":\"generic\",\"hint\":\"boom\"}"
               (:data error))))))

(t/deftest content-type-is-event-stream-on-both-formats
  (let [check (fn [request]
                (let [response ((sse/response (make-handler)) request)]
                  (get (::yres/headers response) "Content-Type")))]
    (t/is (= "text/event-stream;charset=UTF-8" (check {})))
    (t/is (= "text/event-stream;charset=UTF-8"
             (check {:headers {"accept" "application/json"}})))))
