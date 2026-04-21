;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns frontend-tests.data.workspace-media-test
  "Integration tests for the chunked-upload logic in
  app.main.data.workspace.media."
  (:require
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.workspace.media :as media]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.http :as http]))

;; ---------------------------------------------------------------------------
;; Local helpers
;; ---------------------------------------------------------------------------

(defn- make-blob
  "Creates a JS Blob of exactly `size` bytes with the given `mtype`."
  [size mtype]
  (let [buf (js/Uint8Array. size)]
    (js/Blob. #js [buf] #js {:type mtype})))

;; ---------------------------------------------------------------------------
;; Small-file path: direct upload (no chunking)
;; ---------------------------------------------------------------------------

(t/deftest small-file-uses-direct-upload
  (t/testing "blobs below chunk-size use :upload-file-media-object directly"
    (t/async done
      (let [file-id   (uuid/next)
            ;; One byte below the threshold so the blob takes the direct path
            blob-size (dec cf/upload-chunk-size)
            blob      (make-blob blob-size "image/jpeg")
            calls     (atom [])

            fetch-mock
            (fn [url _opts]
              (let [cmd (http/url->cmd url)]
                (swap! calls conj cmd)
                (js/Promise.resolve
                 (http/make-json-response
                  {:id      (str (uuid/next))
                   :name    "img"
                   :width   100
                   :height  100
                   :mtype   "image/jpeg"
                   :file-id (str file-id)}))))

            orig (http/install-fetch-mock! fetch-mock)]

        (->> (media/process-blobs
              {:file-id  file-id
               :local?   true
               :blobs    [blob]
               :on-image (fn [_] nil)
               :on-svg   (fn [_] nil)})
             (rx/subs!
              (fn [_] nil)
              (fn [err]
                (t/is false (str "unexpected error: " (ex-message err)))
                (done))
              (fn []
                (http/restore-fetch! orig)
                ;; Should call :upload-file-media-object, NOT the chunked API
                (t/is (= 1 (count @calls)))
                (t/is (= :upload-file-media-object (first @calls)))
                (done))))))))

;; ---------------------------------------------------------------------------
;; Large-file path: chunked upload via uploads namespace
;; ---------------------------------------------------------------------------

(t/deftest large-file-uses-chunked-upload
  (t/testing "blobs at or above chunk-size use the three-step session API"
    (t/async done
      (let [file-id    (uuid/next)
            session-id (uuid/next)
            chunk-size cf/upload-chunk-size
            ;; Exactly two full chunks
            blob-size  (* 2 chunk-size)
            blob       (make-blob blob-size "image/jpeg")
            calls      (atom [])

            fetch-mock
            (fn [url _opts]
              (let [cmd (http/url->cmd url)]
                (swap! calls conj cmd)
                (js/Promise.resolve
                 (http/make-json-response
                  (case cmd
                    :create-upload-session
                    {:session-id (str session-id)}

                    :upload-chunk
                    {:session-id (str session-id) :index 0}

                    :assemble-file-media-object
                    {:id      (str (uuid/next))
                     :name    "img"
                     :width   100
                     :height  100
                     :mtype   "image/jpeg"
                     :file-id (str file-id)}

                    ;; Default: return an error response
                    {:error (str "unexpected cmd: " cmd)})))))

            orig (http/install-fetch-mock! fetch-mock)]

        (->> (media/process-blobs
              {:file-id  file-id
               :local?   true
               :blobs    [blob]
               :on-image (fn [_] nil)
               :on-svg   (fn [_] nil)})
             (rx/subs!
              (fn [_] nil)
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
                  ;; Last call must assemble
                  (t/is (= :assemble-file-media-object (last cmd-seq)))
                  ;; Direct upload must NOT be called
                  (t/is (not (some #(= :upload-file-media-object %) cmd-seq))))
                (done))))))))

(t/deftest chunked-upload-chunk-count-matches-blob
  (t/testing "number of chunk upload calls equals ceil(blob-size / chunk-size)"
    (t/async done
      (let [file-id     (uuid/next)
            session-id  (uuid/next)
            chunk-size  cf/upload-chunk-size
            ;; Three chunks: 2 full + 1 partial
            blob-size   (+ (* 2 chunk-size) 1)
            blob        (make-blob blob-size "image/jpeg")
            chunk-calls (atom 0)

            fetch-mock
            (fn [url _opts]
              (let [cmd (http/url->cmd url)]
                (js/Promise.resolve
                 (http/make-json-response
                  (case cmd
                    :create-upload-session
                    {:session-id (str session-id)}

                    :upload-chunk
                    (do (swap! chunk-calls inc)
                        {:session-id (str session-id) :index 0})

                    :assemble-file-media-object
                    {:id      (str (uuid/next))
                     :name    "img"
                     :width   100
                     :height  100
                     :mtype   "image/jpeg"
                     :file-id (str file-id)}

                    {:error (str "unexpected cmd: " cmd)})))))

            orig (http/install-fetch-mock! fetch-mock)]

        (->> (media/process-blobs
              {:file-id  file-id
               :local?   true
               :blobs    [blob]
               :on-image (fn [_] nil)
               :on-svg   (fn [_] nil)})
             (rx/subs!
              (fn [_] nil)
              (fn [err]
                (t/is false (str "unexpected error: " (ex-message err)))
                (done))
              (fn []
                (http/restore-fetch! orig)
                (t/is (= 3 @chunk-calls))
                (done))))))))
