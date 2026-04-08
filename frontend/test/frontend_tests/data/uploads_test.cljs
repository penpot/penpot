;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.uploads-test
  "Integration tests for the generic chunked-upload logic in
  app.main.data.uploads."
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.uploads :as uploads]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.http :as http]))

;; ---------------------------------------------------------------------------
;; Local helpers
;; ---------------------------------------------------------------------------

(defn- make-blob
  "Creates a JS Blob of exactly `size` bytes."
  [size]
  (let [buf (js/Uint8Array. size)]
    (js/Blob. #js [buf] #js {:type "application/octet-stream"})))

;; ---------------------------------------------------------------------------
;; upload-blob-chunked tests
;; ---------------------------------------------------------------------------

(t/deftest upload-blob-chunked-creates-session-and-uploads-chunks
  (t/testing "upload-blob-chunked calls create-upload-session then upload-chunk for each slice"
    (t/async done
      (let [session-id  (uuid/next)
            chunk-size  cf/upload-chunk-size
            ;; Exactly two full chunks
            blob-size   (* 2 chunk-size)
            blob        (make-blob blob-size)
            calls       (atom [])

            fetch-mock
            (fn [url _opts]
              (let [cmd (http/url->cmd url)]
                (swap! calls conj cmd)
                (js/Promise.resolve
                 (case cmd
                   :create-upload-session
                   (http/make-transit-response
                    {:session-id session-id})

                   :upload-chunk
                   (http/make-transit-response
                    {:session-id session-id :index 0})

                   (http/make-json-response
                    {:error (str "unexpected cmd: " cmd)})))))

            orig (http/install-fetch-mock! fetch-mock)]

        (->> (uploads/upload-blob-chunked blob)
             (rx/subs!
              (fn [{:keys [session-id]}]
                (t/is (uuid? session-id)))
              (fn [err]
                (t/is false (str "unexpected error: " (ex-message err)))
                (done))
              (fn []
                (http/restore-fetch! orig)
                (let [cmd-seq @calls]
                  ;; First call must create the session
                  (t/is (= :create-upload-session (first cmd-seq)))
                  ;; Two chunk uploads
                  (t/is (= 2 (count (filter #(= :upload-chunk %) cmd-seq))))
                  ;; No assemble call here — that's the caller's responsibility
                  (t/is (not (some #(= :assemble-file-media-object %) cmd-seq))))
                (done))))))))

(t/deftest upload-blob-chunked-chunk-count-matches-blob
  (t/testing "number of upload-chunk calls equals ceil(blob-size / chunk-size)"
    (t/async done
      (let [session-id  (uuid/next)
            chunk-size  cf/upload-chunk-size
            ;; Three chunks: 2 full + 1 partial
            blob-size   (+ (* 2 chunk-size) 1)
            blob        (make-blob blob-size)
            chunk-calls (atom 0)

            fetch-mock
            (fn [url _opts]
              (let [cmd (http/url->cmd url)]
                (js/Promise.resolve
                 (case cmd
                   :create-upload-session
                   (http/make-transit-response
                    {:session-id session-id})

                   :upload-chunk
                   (do (swap! chunk-calls inc)
                       (http/make-transit-response
                        {:session-id session-id :index 0}))

                   (http/make-json-response
                    {:error (str "unexpected cmd: " cmd)})))))

            orig (http/install-fetch-mock! fetch-mock)]

        (->> (uploads/upload-blob-chunked blob)
             (rx/subs!
              (fn [_] nil)
              (fn [err]
                (t/is false (str "unexpected error: " (ex-message err)))
                (done))
              (fn []
                (http/restore-fetch! orig)
                (t/is (= 3 @chunk-calls))
                (done))))))))
