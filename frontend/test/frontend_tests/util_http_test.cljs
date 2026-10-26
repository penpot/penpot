;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.util-http-test
  (:require
   [app.util.http :as http]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]))

(def ^:private uri "https://example.com/assets/file.json")

(defn- failing-response
  "A response map whose native body read rejects with `cause`."
  [cause]
  {:status 200
   :uri uri
   :headers {}
   ::http/response #js {:text (fn [] (js/Promise.reject cause))}})

(defn- read-error
  "Read the body of `response` as text and resolve with the error raised."
  [response]
  (js/Promise.
   (fn [resolve]
     (rx/subs! (fn [_] (resolve nil))
               resolve
               (http/process-response-type :text response)))))

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
