;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-media-test
  "Integration tests for the chunked-upload logic in
  app.main.data.workspace.media."
  (:require
   [app.common.path-names :as cpn]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.workspace.media :as media]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.http :as http]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]
   [tubax.core :as tubax]))

(t/use-fixtures :each
  {:before cthi/reset-idmap!})

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

;; ---------------------------------------------------------------------------
;; SVG component creation: any file extension must be stripped from both
;; the component name and the main instance root shape name.
;; ---------------------------------------------------------------------------

(def ^:private svg-content
  (str "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">"
       "<rect x=\"0\" y=\"0\" width=\"100\" height=\"100\"/>"
       "</svg>"))

;; The `sax` XML parser used by `tubax/xml->clj` is not available in the
;; ESM test build, so the parse step is mocked with an equivalent tree.
;; Everything else (name handling included) runs for real.
(def ^:private parsed-svg
  {:tag :svg
   :attrs {:xmlns "http://www.w3.org/2000/svg"
           :width "100" :height "100"
           :viewBox "0 0 100 100"}
   :content [{:tag :rect
              :attrs {:x "0" :y "0" :width "100" :height "100"}
              :content []}]})

(t/deftest ^:async upload-svg-component-strips-svg-extension
  (t/testing "creating a component from an uploaded SVG removes the extension from both names"
    ;; Any extension must be stripped (not only ".svg"), including
    ;; uppercase ones, extensions in the middle of the name, and
    ;; filenames with no extension at all.
    (doseq [[filename expected]
            [["my-component.svg" "my-component"]
             ["icon.SVG" "icon"]
             ["my.svg.icon.svg" "my.svg.icon"]
             ["design" "design"]]]
      (let [file  (cthf/sample-file :file1)
            store (ths/setup-store file)
            blob  (js/File. #js [svg-content]
                            filename
                            #js {:type "image/svg+xml"})]
        (await
         (mock/with-mocks*
           {st/state       store
            st/emit!       (mock/stub (fn [& events] (apply ptk/emit! store events)))
            tubax/xml->clj (mock/stub (fn [_source] parsed-svg))}
           ;; The SVG is placed at the viewport center; without a vbox the
           ;; geometry setup would receive nil coordinates.
           (ptk/emit! store #(assoc % :workspace-local {:vbox {:x 0 :y 0 :width 800 :height 600}}))
           (ptk/emit! store (media/upload-media-components {:file-id (:id file)
                                                            :blobs   [blob]}))
           (await (async/wait-for #(seq (get-in @store [:files (:id file) :data :components]))
                                  "component created"))
           (let [data      (get-in @store [:files (:id file) :data])
                 component (first (vals (:components data)))
                 root      (get-in data [:pages-index (:main-instance-page component)
                                         :objects (:main-instance-id component)])]
             (t/is (some? component) (str "component not created for " filename))
             (t/is (some? root) (str "root not created for " filename))
             (t/is (= expected (:name component)))
             (t/is (= expected (:name root)))
             (t/is (= expected (cpn/merge-path-item (:path component) (:name component)))))))))))
