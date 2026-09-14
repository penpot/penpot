;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns backend-tests.storage-metadata-test
  (:require
   [app.config :as cf]
   [app.db :as db]
   [app.storage.schema :as stsch]
   [clojure.string :as str]
   [clojure.test :as t])
  (:import
   org.postgresql.util.PGobject))

(defn- pgobject
  [^String value]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue value)))

;; Raw Transit payloads shaped like the production rows sampled on
;; 2026-09-11 (verbose transit: "~:key" keys, "~u<uuid>" uuid values,
;; "~:xxx" keyword values).
(def ^:private transit-media
  "{\"~:hash\":\"blake2b:9f1c2e\",\"~:bucket\":\"file-media-object\",\"~:content-type\":\"image/png\"}")

(def ^:private transit-tempfile
  "{\"~:hash\":\"blake2b:9f1c2e\",\"~:bucket\":\"tempfile\",\"~:content-type\":\"application/zip\",\"~:profile-id\":\"~u86907e95-1cb8-8122-8008-4eb7ba07d89d\"}")

(def ^:private transit-legacy-reference
  "{\"~:reference\":\"~:file-media-object\",\"~:content-type\":\"image/png\",\"~:hash\":\"blake2b:9f1c2e\"}")

(def ^:private transit-no-bucket
  "{\"~:content-type\":\"image/svg+xml\"}")

(def ^:private transit-chunk-leftovers
  "{\"~:bucket\":\"tempfile\",\"~:content-type\":\"application/zip\",\"~:upload-id\":\"~u86907e95-1cb8-8122-8008-4eb7ba07d89d\",\"~:chunk-index\":3}")

(def ^:private json-file-data
  "{\"bucket\":\"file-data\",\"content-type\":\"application/octet-stream\",\"file-id\":\"86907e95-1cb8-8122-8008-4eb7ba07d89d\",\"id\":\"83df2f92-6bd4-4e6d-9c9a-3f6d2b1a4c55\"}")

(t/deftest decode-transit-returns-native-types
  (let [mdata (stsch/decode-metadata (pgobject transit-tempfile))]
    (t/is (= "tempfile" (:bucket mdata)))
    (t/is (= "application/zip" (:content-type mdata)))
    (t/is (= "blake2b:9f1c2e" (:hash mdata)))
    (t/is (uuid? (:profile-id mdata)))
    (t/is (= (parse-uuid "86907e95-1cb8-8122-8008-4eb7ba07d89d")
             (:profile-id mdata)))))

(t/deftest decode-transit-maps-reference-to-bucket
  (let [mdata (stsch/decode-metadata (pgobject transit-legacy-reference))]
    (t/is (= "file-media-object" (:bucket mdata)))
    (t/is (= "image/png" (:content-type mdata)))
    (t/is (not (contains? mdata :reference)))))

(t/deftest decode-transit-defaults-missing-bucket
  (let [mdata (stsch/decode-metadata (pgobject transit-no-bucket))]
    (t/is (= "file-media-object" (:bucket mdata)))
    (t/is (= "image/svg+xml" (:content-type mdata)))))

(t/deftest decode-transit-drops-chunk-leftovers
  (let [mdata (stsch/decode-metadata (pgobject transit-chunk-leftovers))]
    (t/is (= "tempfile" (:bucket mdata)))
    (t/is (not (contains? mdata :upload-id)))
    (t/is (not (contains? mdata :chunk-index)))))

(t/deftest decode-json-coerces-uuids
  (let [mdata (stsch/decode-metadata (pgobject json-file-data))]
    (t/is (= "file-data" (:bucket mdata)))
    (t/is (uuid? (:file-id mdata)))
    (t/is (uuid? (:id mdata)))
    (t/is (= (parse-uuid "86907e95-1cb8-8122-8008-4eb7ba07d89d")
             (:file-id mdata)))))

(t/deftest sniff-has-no-false-positives-on-values
  ;; "~:" inside a value (not anchored to a quote) must not trigger
  ;; the transit branch.
  (let [mdata (stsch/decode-metadata
               (pgobject "{\"bucket\":\"tempfile\",\"content-type\":\"text/plain;~:x\"}"))]
    (t/is (= "tempfile" (:bucket mdata)))
    (t/is (= "text/plain;~:x" (:content-type mdata)))))

(t/deftest encode-writes-transit-by-default
  ;; Pinned off: without the binding this test inherits the ambient
  ;; config and proves nothing where the flag is set.
  (binding [cf/config (assoc cf/config :storage-metadata-as-json nil)]
    (let [encoded (stsch/encode-metadata {:bucket "file-media-object"
                                          :content-type "image/png"
                                          :hash "blake2b:9f1c2e"})
          value   (.getValue ^PGobject encoded)]
      (t/is (string? value))
      (t/is (str/includes? value "\"~:bucket\""))
      (t/is (not (str/includes? value "\"~:reference\""))))))

(t/deftest encode-normalizes-legacy-input
  (binding [cf/config (assoc cf/config :storage-metadata-as-json nil)]
    (let [encoded (stsch/encode-metadata {:reference :file-media-object
                                          :content-type "image/png"})
          value   (.getValue ^PGobject encoded)
          decoded (stsch/decode-metadata encoded)]
      (t/is (= "file-media-object" (:bucket decoded)))
      (t/is (not (str/includes? value "\"~:reference\""))))))

(t/deftest encode-writes-plain-json-with-flag
  (binding [cf/config (assoc cf/config :storage-metadata-as-json true)]
    (let [file-id "86907e95-1cb8-8122-8008-4eb7ba07d89d"
          encoded (stsch/encode-metadata {:bucket "file-data"
                                          :content-type "application/octet-stream"
                                          :file-id file-id
                                          :id "83df2f92-6bd4-4e6d-9c9a-3f6d2b1a4c55"})
          value   (.getValue ^PGobject encoded)]
      (t/is (str/includes? value "\"bucket\""))
      (t/is (not (str/includes? value "\"~:")))
      ;; uuids travel as plain strings on the JSON encoding
      (t/is (str/includes? value file-id))
      (let [decoded (stsch/decode-metadata encoded)]
        (t/is (uuid? (:file-id decoded)))
        (t/is (= (parse-uuid file-id) (:file-id decoded)))))))

(t/deftest encode-accepts-string-uuids
  (binding [cf/config (assoc cf/config :storage-metadata-as-json nil)]
    (let [decoded (stsch/decode-metadata
                   (stsch/encode-metadata {:bucket "tempfile"
                                           :content-type "application/zip"
                                           :profile-id "86907e95-1cb8-8122-8008-4eb7ba07d89d"}))]
      (t/is (uuid? (:profile-id decoded))))))

(t/deftest encode-rejects-unknown-bucket
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid storage object metadata"
                          (stsch/encode-metadata {:bucket "no-such-bucket"
                                                  :content-type "image/png"}))))

(t/deftest encode-rejects-extra-key
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid storage object metadata"
                          (stsch/encode-metadata {:bucket "file-media-object"
                                                  :content-type "image/png"
                                                  :other "data"}))))

(t/deftest encode-rejects-malformed-uuid
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid storage object metadata"
                          (stsch/encode-metadata {:bucket "file-data"
                                                  :content-type "application/octet-stream"
                                                  :file-id "not-a-uuid"
                                                  :id "83df2f92-6bd4-4e6d-9c9a-3f6d2b1a4c55"}))))

(t/deftest encode-rejects-missing-content-type
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid storage object metadata"
                          (stsch/encode-metadata {:bucket "file-media-object"}))))

(t/deftest encode-rejects-missing-file-data-ids
  (t/is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"invalid storage object metadata"
                          (stsch/encode-metadata {:bucket "file-data"
                                                  :content-type "application/octet-stream"
                                                  :file-id "86907e95-1cb8-8122-8008-4eb7ba07d89d"}))))

(t/deftest decode-real-transit-payload
  ;; Same legacy shape as above but produced by the real transit
  ;; encoder, so the test breaks if the encoding ever drifts.
  (let [mdata (stsch/decode-metadata
               (db/tjson {:reference :team-font-variant
                          :content-type "font/woff2"}))]
    (t/is (= "team-font-variant" (:bucket mdata)))
    (t/is (not (contains? mdata :reference)))))

(t/deftest transit-and-json-encodings-decode-to-same-shape
  (let [mdata   {:bucket "tempfile"
                 :content-type "application/zip"
                 :profile-id (parse-uuid "86907e95-1cb8-8122-8008-4eb7ba07d89d")}
        transit (binding [cf/config (assoc cf/config :storage-metadata-as-json nil)]
                  (stsch/decode-metadata (stsch/encode-metadata mdata)))
        json    (binding [cf/config (assoc cf/config :storage-metadata-as-json true)]
                  (stsch/decode-metadata (stsch/encode-metadata mdata)))]
    (t/is (= transit json))
    (t/is (= mdata json))))

(t/deftest flag-on-and-off-differ-only-in-encoding-family
  ;; The Phase 2 rollback contract: flipping the flag changes how the
  ;; same logical metadata hits the disk, never what it means.
  (let [mdata   {:bucket "file-media-object"
                 :content-type "image/png"
                 :hash "blake2b:9f1c2e"}
        transit (binding [cf/config (assoc cf/config :storage-metadata-as-json nil)]
                  (.getValue ^PGobject (stsch/encode-metadata mdata)))
        json    (binding [cf/config (assoc cf/config :storage-metadata-as-json true)]
                  (.getValue ^PGobject (stsch/encode-metadata mdata)))]
    (t/is (str/includes? transit "\"~:bucket\""))
    (t/is (str/includes? json "\"bucket\""))
    (t/is (not (str/includes? json "\"~:")))
    (t/is (= (stsch/decode-metadata (pgobject transit))
             (stsch/decode-metadata (pgobject json))))))

(t/deftest decode-json-keeps-hash-byte-exact
  ;; Dedup matches on the hash string; it must survive the JSON
  ;; roundtrip untouched.
  (let [mdata (stsch/decode-metadata
               (pgobject "{\"bucket\":\"file-media-object\",\"content-type\":\"image/png\",\"hash\":\"blake2b:9f1c2e\"}"))]
    (t/is (= "blake2b:9f1c2e" (:hash mdata)))))
