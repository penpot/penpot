;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.storage.schema
  "Schema, validation and encode/decode helpers for
  `storage_object.metadata`.

  Phase 1: reads accept both the legacy Transit encoding and plain
  JSON (sniffed by the `\"~:` marker); writes validate + normalize
  but still serialize as Transit unless the
  `:storage-metadata-as-json` config flag is set."
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.schema :as sm]
   [app.config :as cf]
   [app.db :as db]
   [cuerdas.core :as str])
  (:import
   org.postgresql.util.PGobject))

(def default-bucket
  "file-media-object")

(def tempfile-bucket
  "Bucket name for temporary file uploads (10-minute expiry)."
  "tempfile")

(def upload-session-bucket
  "Bucket name for chunked-upload chunks."
  "upload-session")

(def metadata-buckets
  "Canonical bucket set. `app.storage/valid-buckets` aliases it so the
  list lives in exactly one place."
  #{"file-media-object" "team-font-variant" "file-object-thumbnail"
    "file-thumbnail" "profile" "organization" tempfile-bucket
    upload-session-bucket "file-data" "file-data-fragment" "file-change"})

(defn- file-data-ids-present?
  "`file-data` rows are resolved by `has-file-data-refs?` in the GC
  through `:file-id` and `:id`; require both for that bucket."
  [{:keys [bucket file-id id]}]
  (or (not= bucket "file-data")
      (and (some? file-id) (some? id))))

(def schema:metadata
  "Closed schema for `storage_object.metadata`. A single shape shared by
  every bucket: `:bucket` is checked against `metadata-buckets` and the
  rest are typed optional keys. Per-bucket requirements are limited to
  `file-data`, which needs both ids so the GC can resolve its references."
  [:and
   [:map {:closed true}
    [:bucket          [::sm/one-of {:format :string} metadata-buckets]]
    [:content-type    :string]
    [:hash            {:optional true} :string]
    [:profile-id      {:optional true} ::sm/uuid]
    [:organization-id {:optional true} ::sm/uuid]
    [:file-id         {:optional true} ::sm/uuid]
    [:id              {:optional true} ::sm/uuid]]
   [:fn {:error/message "file-data metadata requires :file-id and :id"}
    file-data-ids-present?]])

(defn- ->bucket
  [v]
  (cond
    (keyword? v) (d/name v)
    (string? v)  v
    :else        (str v)))

(defn- normalize-metadata
  "Bring decoded (or incoming) metadata to its canonical shape:
  `:bucket` is always present (`:reference` is mapped to it, keyword
  or string, and defaults to `default-bucket`), and the legacy
  `:reference`, `:upload-id` and `:chunk-index` keys are dropped."
  [mdata]
  (let [bucket (or (not-empty (->bucket (:bucket mdata)))
                   (not-empty (->bucket (:reference mdata)))
                   default-bucket)]
    (-> mdata
        (dissoc :reference :upload-id :chunk-index)
        (assoc :bucket bucket))))

;; Decoder and encoder are built once per process: creating them
;; compiles the (closed, multi-dispatch) schema, and `decode-metadata`
;; runs on every read path (get-object, dedup probes, GC batches).
(def ^:private decode-metadata*
  (sm/decode-fn schema:metadata (sm/json-transformer)))

(def ^:private encode-metadata*
  (sm/encoder schema:metadata (sm/json-transformer)))

(defn- transit-encoded?
  "Legacy metadata is Transit json-verbose, where every top-level key is
  a keyword, so the document starts with `{\"~:`. Anchoring the check to
  the start avoids reading a plain-JSON document as Transit just because
  one of its values contains `\"~:`."
  [value]
  (and (string? value)
       (str/starts-with? (str/trim value) "{\"~:")))

(defn decode-metadata
  "Decode storage metadata from a PGobject, accepting both the legacy
  Transit encoding (sniffed by the `\"~:` marker) and plain JSON.
  Always returns the normalized shape with id fields as UUIDs."
  [o]
  (when (some? o)
    (let [raw (if (transit-encoded? (.getValue ^PGobject o))
                (db/decode-transit-pgobject o)
                (db/decode-json-pgobject o))]
      (when-not (map? raw)
        (ex/raise :type :internal
                  :code :invalid-storage-metadata
                  :hint "expected a map on storage object metadata"))
      (decode-metadata* (normalize-metadata raw)))))

(def ^:private check-metadata
  (sm/check-fn schema:metadata
               :hint "invalid storage object metadata"
               :type :validation
               :code :invalid-storage-metadata))

(defn encode-metadata
  "Validate, normalize and encode storage metadata into a PGobject
  ready for the `metadata` column. Serializes as Transit while the
  `:storage-metadata-as-json` config flag is unset, as plain JSON
  when it is set."
  [mdata]
  (let [mdata (check-metadata (decode-metadata* (normalize-metadata mdata)))]
    (if (cf/get :storage-metadata-as-json)
      (db/json (encode-metadata* mdata))
      (db/tjson mdata))))
