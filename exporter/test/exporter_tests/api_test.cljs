;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter-tests.api-test
  "The management client of the worker against a fake http: what it
  sends (method uri, shared key, shape of the body) is the whole
  contract this client owns."
  (:require
   ["node:fs/promises" :as fsp]
   ["node:path" :as path]
   ["undici" :as http]
   [app.common.exceptions :as ex]
   [app.common.transit :as transit]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.consumer.api :as api]
   [app.util.shell :as sh]
   [cljs.core :as c]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(def ^:private original-fetch api/fetch)

(def ^:private calls
  "Every fetch the client made, in order."
  (atom []))

(defn- fake-fetch
  [uri request]
  (swap! calls conj [uri request])
  (p/resolved #js {:status 200
                   :text   (constantly (p/resolved (transit/encode-str {:action :run})))}))

(defn- failure-fetch
  "A fake that answers like a management endpoint that refuses the key."
  [_uri _request]
  (p/resolved #js {:status 403
                   :text   (constantly (p/resolved "{}"))}))

(defn- install-fetch!
  "Put a fake in place and return the thunk that puts the original
  back. The thunk belongs at the end of the promise chain's `p/do`, so
  the fake covers the whole async run."
  ([] (install-fetch! fake-fetch))
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

(defn- with-restore-and-done
  "Run `thunk`, then put the original fetch back, then say the test is
  finished: the rest never waits on the assertions, so a broken chain
  reports a failure, never a hang."
  [thunk restore! done]
  (p/do thunk (restore!) (done)))

(t/deftest claim-job-posts-the-claim-of-one-poller
  (t/async done
    (let [job-id   (uuid/next)
          restore! (install-fetch!)]
      (with-restore-and-done
        (p/fmap (fn [_]
                  (t/is (str/ends-with? (last-uri) "/api/management/methods/claim-job"))
                  (t/is (= (str "exporter " cf/management-key) (fetch-header "X-Shared-Key")))
                  (t/is (= {:job-id job-id :scheduled-at "2026-10-07T00:00:00Z"}
                           (decode-body))))
                (api/claim-job job-id "2026-10-07T00:00:00Z"))
        restore!
        done))))

(t/deftest json-calls-declare-the-transit-content-type
  ;; without it the backend never parses the body as transit and
  ;; validates an empty map (every key a `missing-key`)
  (t/async done
    (let [restore! (install-fetch!)]
      (with-restore-and-done
        (p/fmap (fn [_]
                  (t/is (= "application/transit+json"
                           (fetch-header "Content-Type"))))
                (api/claim-job (uuid/next) "2026-10-07T00:00:00Z"))
        restore!
        done))))

(t/deftest report-job-progress-posts-the-beat
  (t/async done
    (let [job-id   (uuid/next)
          progress {:stage :rendering
                    :counters {:objects {:current 1 :total 2}}}
          restore! (install-fetch!)]
      (with-restore-and-done
        (p/fmap (fn [_]
                  (t/is (str/ends-with? (last-uri) "/api/management/methods/report-job-progress"))
                  (t/is (= {:job-id job-id :progress progress} (decode-body))))
                (api/report-job-progress job-id progress))
        restore!
        done))))

(t/deftest fail-job-carries-the-error-and-the-session
  (t/async done
    (let [job-id   (uuid/next)
          sid      (uuid/next)
          error    {:type :internal :code :processing-error :hint "boom"}
          restore! (install-fetch!)]
      (with-restore-and-done
        (p/mcat (fn [_]
                  (p/fmap (fn [_]
                            (t/is (= {:job-id job-id :error error :session-id sid}
                                     (decode-body))))
                          (api/fail-job job-id error :session-id sid)))
                (api/fail-job job-id error))
        restore!
        done))))

(t/deftest complete-job-posts-the-plain-result
  (t/async done
    (let [job-id   (uuid/next)
          sid      (uuid/next)
          restore! (install-fetch!)]
      (with-restore-and-done
        (p/mcat (fn [_]
                  (p/fmap (fn [_]
                            (t/is (= {:job-id job-id :result {:total 1} :session-id sid}
                                     (decode-body))))
                          (api/complete-job job-id {:total 1} {:session-id sid})))
                (api/complete-job job-id {:total 1}))
        restore!
        done))))

(t/deftest complete-job-with-artifact-rides-multipart
  (t/async done
    (let [job-id   (uuid/next)
          sid      (uuid/next)
          tmpfile  (path/join sh/tmpdir (str "test-artifact." (uuid/next)))
          restore! (install-fetch!)]
      (with-restore-and-done
        (p/mcat (fn [_]
                  (p/fmap (fn [_]
                            (t/is (instance? http/FormData (.-body (last-request))))
                            (t/is (= (str job-id) (body-value "job-id")))
                            (t/is (= "export.zip" (body-value "filename")))
                            (t/is (= "application/zip" (body-value "mtype")))
                            (t/is (= (str sid) (body-value "session-id")))
                            (t/is (= (str "exporter " cf/management-key)
                                     (fetch-header "X-Shared-Key"))))
                          (api/complete-job-with-artifact
                           {:job-id job-id :session-id sid}
                           {:path     tmpfile
                            :filename "export.zip"
                            :mtype    "application/zip"})))
                (->> (fsp/writeFile tmpfile "the export bytes")
                     (p/fmap (fn [_] nil))))
        restore!
        done))))

(t/deftest a-failed-management-answer-is-the-failure-of-the-call
  (t/async done
    (let [restore! (install-fetch! failure-fetch)]
      (with-restore-and-done
        (p/catch (fn [error]
                   (let [data (ex-data error)]
                     (t/is (= :internal (:type data)))
                     (t/is (= :failed-management-request (:code data)))
                     (t/is (= 403 (:status data)))))
                 (api/claim-job (uuid/next) "2026-10-07T00:00:00Z"))
        restore!
        done))))
