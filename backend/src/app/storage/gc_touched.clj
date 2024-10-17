;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.storage.gc-touched
  "This task is part of the garbage collection process of storage
  objects and is responsible on analyzing the touched objects and mark
  them for deletion if corresponds.

  For example: when file_media_object is deleted, the depending
  storage_object are marked as touched. This means that some files
  that depend on a concrete storage_object are no longer exists and
  maybe this storage_object is no longer necessary and can be eligible
  for elimination. This task periodically analyzes touched objects and
  mark them as freeze (means that has other references and the object
  is still valid) or deleted (no more references to this object so is
  ready to be deleted)."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.db :as db]
   [app.storage :as-alias sto]
   [app.storage.impl :as impl]
   [clojure.spec.alpha :as s]
   [integrant.core :as ig]))

(def ^:private sql:has-team-font-variant-refs
  "SELECT ((SELECT EXISTS (SELECT 1 FROM team_font_variant WHERE woff1_file_id = ?)) OR
           (SELECT EXISTS (SELECT 1 FROM team_font_variant WHERE woff2_file_id = ?)) OR
           (SELECT EXISTS (SELECT 1 FROM team_font_variant WHERE otf_file_id = ?)) OR
           (SELECT EXISTS (SELECT 1 FROM team_font_variant WHERE ttf_file_id = ?))) AS has_refs")

(defn- has-team-font-variant-refs?
  [conn id]
  (-> (db/exec-one! conn [sql:has-team-font-variant-refs id id id id])
      (get :has-refs)))

(def ^:private
  sql:has-file-media-object-refs
  "SELECT ((SELECT EXISTS (SELECT 1 FROM file_media_object WHERE media_id = ?)) OR
           (SELECT EXISTS (SELECT 1 FROM file_media_object WHERE thumbnail_id = ?))) AS has_refs")

(defn- has-file-media-object-refs?
  [conn id]
  (-> (db/exec-one! conn [sql:has-file-media-object-refs id id])
      (get :has-refs)))

(def ^:private sql:has-profile-refs
  "SELECT ((SELECT EXISTS (SELECT 1 FROM profile WHERE photo_id = ?)) OR
           (SELECT EXISTS (SELECT 1 FROM team WHERE photo_id = ?))) AS has_refs")

(defn- has-profile-refs?
  [conn id]
  (-> (db/exec-one! conn [sql:has-profile-refs id id])
      (get :has-refs)))

(def ^:private
  sql:has-file-object-thumbnail-refs
  "SELECT EXISTS (SELECT 1 FROM file_tagged_object_thumbnail WHERE media_id = ?) AS has_refs")

(defn- has-file-object-thumbnails-refs?
  [conn id]
  (-> (db/exec-one! conn [sql:has-file-object-thumbnail-refs id])
      (get :has-refs)))

(def ^:private
  sql:has-file-thumbnail-refs
  "SELECT EXISTS (SELECT 1 FROM file_thumbnail WHERE media_id = ?) AS has_refs")

(defn- has-file-thumbnails-refs?
  [conn id]
  (-> (db/exec-one! conn [sql:has-file-thumbnail-refs id])
      (get :has-refs)))

(def ^:private
  sql:has-file-data-refs
  "SELECT EXISTS (SELECT 1 FROM file WHERE data_ref_id = ?) AS has_refs")

(defn- has-file-data-refs?
  [conn id]
  (-> (db/exec-one! conn [sql:has-file-data-refs id])
      (get :has-refs)))

(def ^:private
  sql:has-file-data-fragment-refs
  "SELECT EXISTS (SELECT 1 FROM file_data_fragment WHERE data_ref_id = ?) AS has_refs")

(defn- has-file-data-fragment-refs?
  [conn id]
  (-> (db/exec-one! conn [sql:has-file-data-fragment-refs id])
      (get :has-refs)))

(def ^:private
  sql:has-file-change-refs
  "SELECT EXISTS (SELECT 1 FROM file_change WHERE data_ref_id = ?) AS has_refs")

(defn- has-file-change-refs?
  [conn id]
  (-> (db/exec-one! conn [sql:has-file-change-refs id])
      (get :has-refs)))

(def ^:private sql:mark-freeze-in-bulk
  "UPDATE storage_object
      SET touched_at = NULL
    WHERE id = ANY(?::uuid[])")

(defn- mark-freeze-in-bulk!
  [conn ids]
  (let [ids (db/create-array conn "uuid" ids)]
    (db/exec-one! conn [sql:mark-freeze-in-bulk ids])))

(def ^:private sql:mark-delete-in-bulk
  "UPDATE storage_object
      SET deleted_at = now(),
          touched_at = NULL
    WHERE id = ANY(?::uuid[])")

(defn- mark-delete-in-bulk!
  [conn ids]
  (let [ids (db/create-array conn "uuid" ids)]
    (db/exec-one! conn [sql:mark-delete-in-bulk ids])))

;; NOTE: A getter that retrieves the key which will be used for group
;; ids; previously we have no value, then we introduced the
;; `:reference` prop, and then it is renamed to `:bucket` and now is
;; string instead. This is implemented in this way for backward
;; comaptibilty.

;; NOTE: we use the "file-media-object" as default value for
;; backward compatibility because when we deploy it we can
;; have old backend instances running in the same time as
;; the new one and we can still have storage-objects created
;; without bucket value.  And we know that if it does not
;; have value, it means :file-media-object.

(defn- lookup-bucket
  [{:keys [metadata]}]
  (or (some-> metadata :bucket)
      (some-> metadata :reference d/name)
      "file-media-object"))

(defn- process-objects!
  [conn has-refs? ids bucket]
  (loop [to-freeze #{}
         to-delete #{}
         ids       (seq ids)]
    (if-let [id (first ids)]
      (if (has-refs? conn id)
        (do
          (l/debug :hint "processing object"
                   :id (str id)
                   :status "freeze"
                   :bucket bucket)
          (recur (conj to-freeze id) to-delete (rest ids)))
        (do
          (l/debug :hint "processing object"
                   :id (str id)
                   :status "delete"
                   :bucket bucket)
          (recur to-freeze (conj to-delete id) (rest ids))))
      (do
        (some->> (seq to-freeze) (mark-freeze-in-bulk! conn))
        (some->> (seq to-delete) (mark-delete-in-bulk! conn))
        [(count to-freeze) (count to-delete)]))))

(defn- process-bucket!
  [conn bucket ids]
  (case bucket
    "file-media-object"     (process-objects! conn has-file-media-object-refs? ids bucket)
    "team-font-variant"     (process-objects! conn has-team-font-variant-refs? ids bucket)
    "file-object-thumbnail" (process-objects! conn has-file-object-thumbnails-refs? ids bucket)
    "file-thumbnail"        (process-objects! conn has-file-thumbnails-refs? ids bucket)
    "profile"               (process-objects! conn has-profile-refs? ids bucket)
    "file-data"             (process-objects! conn has-file-data-refs? ids bucket)
    "file-data-fragment"    (process-objects! conn has-file-data-fragment-refs? ids bucket)
    "file-change"           (process-objects! conn has-file-change-refs? ids bucket)
    (ex/raise :type :internal
              :code :unexpected-unknown-reference
              :hint (dm/fmt "unknown reference '%'" bucket))))

(defn process-chunk!
  [{:keys [::db/conn]} chunk]
  (reduce-kv (fn [[nfo ndo] bucket ids]
               (let [[nfo' ndo'] (process-bucket! conn bucket ids)]
                 [(+ nfo nfo')
                  (+ ndo ndo')]))
             [0 0]
             (d/group-by lookup-bucket :id #{} chunk)))

(def ^:private
  sql:get-touched-storage-objects
  "SELECT so.*
     FROM storage_object AS so
    WHERE so.touched_at IS NOT NULL
    ORDER BY touched_at ASC
      FOR UPDATE
     SKIP LOCKED
    LIMIT 10")

(defn get-chunk
  [conn]
  (->> (db/exec! conn [sql:get-touched-storage-objects])
       (map impl/decode-row)
       (not-empty)))

(defn- process-touched!
  [{:keys [::db/pool] :as cfg}]
  (loop [freezed 0
         deleted 0]
    (if-let [chunk (get-chunk pool)]
      (let [[nfo ndo] (db/tx-run! cfg process-chunk! chunk)]
        (recur (+ freezed nfo)
               (+ deleted ndo)))
      (do
        (l/inf :hint "task finished"
               :to-freeze freezed
               :to-delete deleted)

        {:freeze freezed :delete deleted}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HANDLER
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod ig/pre-init-spec ::handler [_]
  (s/keys :req [::db/pool]))

(defmethod ig/init-key ::handler
  [_ cfg]
  (fn [_] (process-touched! cfg)))

