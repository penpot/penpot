;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.consumer-api-test
  "The management client of the new tree against a fake http: what it
  sends (method uri, shared key, shape of the body) is the whole
  contract this client owns."
  (:require
   ["node:fs/promises" :as fsp]
   ["node:os" :as os]
   ["node:path" :as path]
   ["undici" :as http]
   [app.common.transit :as transit]
   [app.common.uuid :as uuid]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [exporter.config :as cf]
   [exporter.consumer.api :as api]))

(def ^:private original-fetch api/fetch)

(def ^:private calls
  "Every fetch the client made, in order."
  (atom []))

(defn- fake-fetch
  [uri request]
  (swap! calls conj [uri request])
  (js/Promise.resolve #js {:status 200
                           :text   (constantly
                                    (js/Promise.resolve
                                     (transit/encode-str {:action :run})))}))

(defn- failure-fetch
  "A fake that answers like a management endpoint that refuses the key."
  [_uri _request]
  (js/Promise.resolve #js {:status 403
                           :text   (constantly (js/Promise.resolve "{}"))}))

(defn- install-fetch
  ([] (install-fetch fake-fetch))
  ([the-fake]
   (set! api/fetch the-fake)
   (reset! calls [])
   (fn [] (set! api/fetch original-fetch))))

(defn- last-request
  []
  (second (last @calls)))

(defn- fetch-header
  "One header of the last request, exactly as it would travel."
  [name]
  (unchecked-get (.-headers (second (last @calls))) name))

(defn- body-value
  "One field of the last FormData request."
  [name]
  (.get (.-body (last-request)) name))

(defn- decode-body
  "The body of the last transit request, decoded."
  []
  (transit/decode-str (.-body (last-request))))

(defn- last-uri
  []
  (first (last @calls)))

(t/deftest ^:async claim-job-posts-the-claim-of-one-poller
  (let [restore (install-fetch)]
    (try
      (let [job-id (uuid/next)]
        (await (api/claim-job job-id "2026-10-07T00:00:00Z"))
        (t/is (str/ends-with? (last-uri) "/api/management/methods/claim-job"))
        (t/is (= (str "exporter " cf/management-key) (fetch-header "X-Shared-Key")))
        (t/is (= {:job-id job-id :scheduled-at "2026-10-07T00:00:00Z"}
                 (decode-body))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore)))))

(t/deftest ^:async json-calls-declare-the-transit-content-type
  ;; without it the backend never parses the body as transit and
  ;; validates an empty map (every key a `missing-key`)
  (let [restore (install-fetch)]
    (try
      (await (api/claim-job (uuid/next) "2026-10-07T00:00:00Z"))
      (t/is (= "application/transit+json"
               (fetch-header "Content-Type")))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore)))))

(t/deftest ^:async report-job-progress-posts-the-beat
  (let [restore (install-fetch)]
    (try
      (let [job-id   (uuid/next)
            progress {:stage    :rendering
                      :counters {:objects {:current 1 :total 2}}}]
        (await (api/report-job-progress job-id progress))
        (t/is (str/ends-with? (last-uri) "/api/management/methods/report-job-progress"))
        (t/is (= {:job-id job-id :progress progress} (decode-body))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore)))))

(t/deftest ^:async fail-job-carries-the-error-and-the-session
  (let [restore (install-fetch)]
    (try
      (let [job-id (uuid/next)
            sid    (uuid/next)
            error  {:type :internal :code :processing-error :hint "boom"}]
        (await (api/fail-job job-id error))
        (await (api/fail-job job-id error :session-id sid))
        (t/is (= {:job-id job-id :error error :session-id sid}
                 (decode-body))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore)))))

(t/deftest ^:async complete-job-posts-the-plain-result
  (let [restore (install-fetch)]
    (try
      (let [job-id (uuid/next)
            sid    (uuid/next)]
        (await (api/complete-job job-id {:total 1}))
        (await (api/complete-job job-id {:total 1} {:session-id sid}))
        (t/is (= {:job-id job-id :result {:total 1} :session-id sid}
                 (decode-body))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore)))))

(t/deftest ^:async complete-job-with-artifact-rides-multipart
  (let [restore (install-fetch)]
    (try
      (let [job-id  (uuid/next)
            sid     (uuid/next)
            tmpfile (path/join (os/tmpdir) (str "test-artifact." (uuid/next)))]
        (await (fsp/writeFile tmpfile "the export bytes"))
        (await (api/complete-job-with-artifact
                {:job-id job-id :session-id sid}
                {:path     tmpfile
                 :filename "export.zip"
                 :mtype    "application/zip"}))
        (t/is (instance? http/FormData (.-body (last-request))))
        (t/is (= (str job-id) (body-value "job-id")))
        (t/is (= "export.zip" (body-value "filename")))
        (t/is (= "application/zip" (body-value "mtype")))
        (t/is (= (str sid) (body-value "session-id")))
        (t/is (= (str "exporter " cf/management-key)
                 (fetch-header "X-Shared-Key"))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore)))))

(t/deftest ^:async a-failed-management-answer-is-the-failure-of-the-call
  (let [restore (install-fetch failure-fetch)]
    (try
      (try
        (await (api/claim-job (uuid/next) "2026-10-07T00:00:00Z"))
        (t/is false "the call should have rejected on a 403")
        (catch :default cause
          (let [data (ex-data cause)]
            (t/is (= :internal (:type data)))
            (t/is (= :failed-management-request (:code data)))
            (t/is (= 403 (:status data))))))
      (catch :default cause
        (t/is false (str "unexpected failure: " (ex-message cause))))
      (finally
        (restore)))))
