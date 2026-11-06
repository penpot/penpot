;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.util-http-test
  (:require
   [app.util.http :as http]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]))

(def ^:private uri "https://example.com/assets/file.json")

(defn- failing-response
  "A response map whose native body read rejects with `cause`."
  [cause]
  {:status 200
   :uri uri
   :headers {}
   ::http/response #js {:text (fn [] (js/Promise.reject cause))}})

(defn- ^:async read-error
  "Read the body of `response` as text and resolve with the error raised."
  [response]
  (try
    (await (async/->promise (http/process-response-type :text response)))
    nil
    (catch :default cause
      cause)))

(t/deftest ^:async body-read-network-failure-is-a-network-error
  (let [cause (js/TypeError. "network error")
        error (await (read-error (failing-response cause)))
        data  (ex-data error)]
    (t/is (= :network (:type data)))
    (t/is (= uri (:uri data)))
    (t/is (identical? cause (ex-cause error)))))

(t/deftest ^:async body-parse-failure-passes-unchanged
  (let [cause (js/SyntaxError. "Unexpected token < in JSON")
        error (await (read-error (failing-response cause)))]
    (t/is (identical? cause error))))

(t/deftest ^:async body-read-programming-error-passes-unchanged
  (let [cause (js/TypeError. "body stream already read")
        error (await (read-error (failing-response cause)))]
    (t/is (identical? cause error))))

;; ---------------------------------------------------------------------------
;; network-failure? tests
;; ---------------------------------------------------------------------------

(defn- make-error
  [error-name message]
  (let [err (js/Error. message)]
    (set! (.-name err) error-name)
    err))

(t/deftest test-network-failure-browser-messages
  (t/testing "The TypeError each browser gives a failed fetch is a network failure"
    (doseq [message ["Failed to fetch"
                     "Failed to fetch (design.penpot.app)"
                     "NetworkError when attempting to fetch resource."
                     "Load failed"
                     "network error"
                     "Error in body stream"]]
      (t/is (true? (http/network-failure? (make-error "TypeError" message)))
            message))))

(t/deftest test-network-failure-needs-type-error
  (t/testing "Other errors with the same message are not network failures"
    (t/is (false? (http/network-failure? (make-error "Error" "Failed to fetch")))))
  (t/testing "Other TypeErrors are not network failures"
    (t/is (false? (http/network-failure? (make-error "TypeError" "x is not a function"))))))
