;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.binfile.common
  "A binfile related file processing common code, used for different
  binfile format implementations and management rpc methods."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.migrations :as fmg]
   [app.common.files.validate :as fval]
   [app.common.logging :as l]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.features.components-v2 :as feat.compv2]
   [app.features.fdata :as feat.fdata]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.set :as set]
   [clojure.walk :as walk]
   [cuerdas.core :as str]))

(set! *warn-on-reflection* true)

(def ^:dynamic *state* nil)
(def ^:dynamic *options* nil)

(def xf-map-id
  (map :id))

(def xf-map-media-id
  (comp
   (mapcat (juxt :media-id
                 :thumbnail-id
                 :woff1-file-id
                 :woff2-file-id
                 :ttf-file-id
                 :otf-file-id))
   (filter uuid?)))

(def into-vec
  (fnil into []))

(def conj-vec
  (fnil conj []))

(defn collect-storage-objects
  [state items]
  (update state :storage-objects into xf-map-media-id items))

(defn collect-summary
  [state key items]
  (update state key into xf-map-media-id items))

(defn lookup-index
  [id]
  (when id
    (let [val (get-in @*state* [:index id])]
      (l/trc :fn "lookup-index" :id (str id) :result (some-> val str) ::l/sync? true)
      (or val id))))

(defn remap-id
  [item key]
  (cond-> item
    (contains? item key)
    (update key lookup-index)))

(defn- index-object
  [index obj & attrs]
  (reduce (fn [index attr-fn]
            (let [old-id (attr-fn obj)
                  new-id (if (::overwrite *options*) old-id (uuid/next))]
              (assoc index old-id new-id)))
          index
          attrs))

(defn update-index
  ([index coll]
   (update-index index coll identity))
  ([index coll attr]
   (reduce #(index-object %1 %2 attr) index coll)))

(defn decode-row
  "A generic decode row helper"
  [{:keys [data features] :as row}]
  (cond-> row
    features (assoc :features (db/decode-pgarray features #{}))
    data     (assoc :data (blob/decode data))))

(defn get-file
  [cfg file-id]
  (db/run! cfg (fn [{:keys [::db/conn] :as cfg}]
                 (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg file-id)]
                   (when-let [file (db/get* conn :file {:id file-id}
                                            {::db/remove-deleted false})]
                     (-> file
                         (decode-row)
                         (update :data feat.fdata/process-pointers deref)
                         (update :data feat.fdata/process-objects (partial into {}))))))))

(defn get-project
  [cfg project-id]
  (db/get cfg :project {:id project-id}))

(defn get-team
  [cfg team-id]
  (-> (db/get cfg :team {:id team-id})
      (decode-row)))

(defn get-fonts
  [cfg team-id]
  (db/query cfg :team-font-variant
            {:team-id team-id
             :deleted-at nil}))

(defn get-files-rels
  "Given a set of file-id's, return all matching relations with the libraries"
  [cfg ids]

  (dm/assert!
   "expected a set of uuids"
   (and (set? ids)
        (every? uuid? ids)))

  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (let [ids (db/create-array conn "uuid" ids)
                       sql (str "SELECT flr.* FROM file_library_rel AS flr "
                                "  JOIN file AS l ON (flr.library_file_id = l.id) "
                                " WHERE flr.file_id = ANY(?) AND l.deleted_at IS NULL")]
                   (db/exec! conn [sql ids])))))

(def ^:private sql:get-libraries
  "WITH RECURSIVE libs AS (
     SELECT fl.id
       FROM file AS fl
       JOIN file_library_rel AS flr ON (flr.library_file_id = fl.id)
      WHERE flr.file_id = ANY(?)
    UNION
     SELECT fl.id
       FROM file AS fl
       JOIN file_library_rel AS flr ON (flr.library_file_id = fl.id)
       JOIN libs AS l ON (flr.file_id = l.id)
   )
   SELECT DISTINCT l.id
     FROM libs AS l")

(defn get-libraries
  "Get all libraries ids related to provided file ids"
  [cfg ids]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (let [ids' (db/create-array conn "uuid" ids)]
                   (->> (db/exec! conn [sql:get-libraries ids'])
                        (into #{} xf-map-id))))))

(defn get-file-object-thumbnails
  "Return all file object thumbnails for a given file."
  [cfg file-id]
  (db/query cfg :file-tagged-object-thumbnail
            {:file-id file-id
             :deleted-at nil}))

(defn get-file-thumbnail
  "Return the thumbnail for the specified file-id"
  [cfg {:keys [id revn]}]
  (db/get* cfg :file-thumbnail
           {:file-id id
            :revn revn
            :data nil}
           {::sql/columns [:media-id :file-id :revn]}))


(def ^:private
  xform:collect-media-id
  (comp
   (map :objects)
   (mapcat vals)
   (mapcat (fn [obj]
             ;; NOTE: because of some bug, we ended with
             ;; many shape types having the ability to
             ;; have fill-image attribute (which initially
             ;; designed for :path shapes).
             (sequence
              (keep :id)
              (concat [(:fill-image obj)
                       (:metadata obj)]
                      (map :fill-image (:fills obj))
                      (map :stroke-image (:strokes obj))
                      (->> (:content obj)
                           (tree-seq map? :children)
                           (mapcat :fills)
                           (map :fill-image))))))))

(defn collect-used-media
  "Given a fdata (file data), returns all media references."
  [data]
  (-> #{}
      (into xform:collect-media-id (vals (:pages-index data)))
      (into xform:collect-media-id (vals (:components data)))
      (into (keys (:media data)))))

(defn get-file-media
  [cfg {:keys [data id] :as file}]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (let [ids (collect-used-media data)
                       ids (db/create-array conn "uuid" ids)
                       sql (str "SELECT * FROM file_media_object WHERE id = ANY(?)")]

                   ;; We assoc the file-id again to the file-media-object row
                   ;; because there are cases that used objects refer to other
                   ;; files and we need to ensure in the exportation process that
                   ;; all ids matches
                   (->> (db/exec! conn [sql ids])
                        (mapv #(assoc % :file-id id)))))))

(def ^:private sql:get-team-files
  "SELECT f.id FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE p.team_id = ?")

(defn get-team-files
  "Get a set of file ids for the specified team-id"
  [{:keys [::db/conn]} team-id]
  (->> (db/exec! conn [sql:get-team-files team-id])
       (into #{} xf-map-id)))

(def ^:private sql:get-team-projects
  "SELECT p.id FROM project AS p
    WHERE p.team_id = ?
      AND p.deleted_at IS NULL")

(defn get-team-projects
  "Get a set of project ids for the team"
  [{:keys [::db/conn]} team-id]
  (->> (db/exec! conn [sql:get-team-projects team-id])
       (into #{} xf-map-id)))

(def ^:private sql:get-project-files
  "SELECT f.id FROM file AS f
    WHERE f.project_id = ?
      AND f.deleted_at IS NULL")

(defn get-project-files
  "Get a set of file ids for the project"
  [{:keys [::db/conn]} project-id]
  (->> (db/exec! conn [sql:get-project-files project-id])
       (into #{} xf-map-id)))

(defn- relink-shapes
  "A function responsible to analyze all file data and
  replace the old :component-file reference with the new
  ones, using the provided file-index."
  [data]
  (letfn [(process-map-form [form]
            (cond-> form
              ;; Relink image shapes
              (and (map? (:metadata form))
                   (= :image (:type form)))
              (update-in [:metadata :id] lookup-index)

              ;; Relink paths with fill image
              (map? (:fill-image form))
              (update-in [:fill-image :id] lookup-index)

              ;; This covers old shapes and the new :fills.
              (uuid? (:fill-color-ref-file form))
              (update :fill-color-ref-file lookup-index)

              ;; This covers the old shapes and the new :strokes
              (uuid? (:stroke-color-ref-file form))
              (update :stroke-color-ref-file lookup-index)

              ;; This covers all text shapes that have typography referenced
              (uuid? (:typography-ref-file form))
              (update :typography-ref-file lookup-index)

              ;; This covers the component instance links
              (uuid? (:component-file form))
              (update :component-file lookup-index)

              ;; This covers the shadows and grids (they have directly
              ;; the :file-id prop)
              (uuid? (:file-id form))
              (update :file-id lookup-index)))

          (process-form [form]
            (if (map? form)
              (try
                (process-map-form form)
                (catch Throwable cause
                  (l/warn :hint "failed form" :form (pr-str form) ::l/sync? true)
                  (throw cause)))
              form))]

    (walk/postwalk process-form data)))

(defn- relink-media
  "A function responsible of process the :media attr of file data and
  remap the old ids with the new ones."
  [media]
  (reduce-kv (fn [res k v]
               (let [id (lookup-index k)]
                 (if (uuid? id)
                   (-> res
                       (assoc id (assoc v :id id))
                       (dissoc k))
                   res)))
             media
             media))

(defn- relink-colors
  "A function responsible of process the :colors attr of file data and
  remap the old ids with the new ones."
  [colors]
  (reduce-kv (fn [res k v]
               (if (:image v)
                 (update-in res [k :image :id] lookup-index)
                 res))
             colors
             colors))

(defn embed-assets
  [cfg data file-id]
  (letfn [(walk-map-form [form state]
            (cond
              (uuid? (:fill-color-ref-file form))
              (do
                (vswap! state conj [(:fill-color-ref-file form) :colors (:fill-color-ref-id form)])
                (assoc form :fill-color-ref-file file-id))

              (uuid? (:stroke-color-ref-file form))
              (do
                (vswap! state conj [(:stroke-color-ref-file form) :colors (:stroke-color-ref-id form)])
                (assoc form :stroke-color-ref-file file-id))

              (uuid? (:typography-ref-file form))
              (do
                (vswap! state conj [(:typography-ref-file form) :typographies (:typography-ref-id form)])
                (assoc form :typography-ref-file file-id))

              (uuid? (:component-file form))
              (do
                (vswap! state conj [(:component-file form) :components (:component-id form)])
                (assoc form :component-file file-id))

              :else
              form))

          (process-group-of-assets [data [lib-id items]]
            ;; NOTE: there is a possibility that shape refers to an
            ;; non-existant file because the file was removed. In this
            ;; case we just ignore the asset.
            (if-let [lib (get-file cfg lib-id)]
              (reduce (partial process-asset lib) data items)
              data))

          (process-asset [lib data [bucket asset-id]]
            (let [asset (get-in lib [:data bucket asset-id])
                  ;; Add a special case for colors that need to have
                  ;; correctly set the :file-id prop (pending of the
                  ;; refactor that will remove it).
                  asset (cond-> asset
                          (= bucket :colors) (assoc :file-id file-id))]
              (update data bucket assoc asset-id asset)))]

    (let [assets (volatile! [])]
      (walk/postwalk #(cond-> % (map? %) (walk-map-form assets)) data)
      (->> (deref assets)
           (filter #(as-> (first %) $ (and (uuid? $) (not= $ file-id))))
           (d/group-by first rest)
           (reduce (partial process-group-of-assets) data)))))

(defn- fix-version
  [file]
  (let [file (fmg/fix-version file)]
    ;; FIXME: We're temporarily activating all migrations because a
    ;; problem in the environments messed up with the version numbers
    ;; When this problem is fixed delete the following line
    (if (> (:version file) 22)
      (assoc file :version 22)
      file)))

(defn process-file
  [{:keys [id] :as file}]
  (-> file
      (fix-version)
      (update :data (fn [fdata]
                      (-> fdata
                          (assoc :id id)
                          (dissoc :recent-colors))))
      (fmg/migrate-file)
      (update :data (fn [fdata]
                      (-> fdata
                          (update :pages-index relink-shapes)
                          (update :components relink-shapes)
                          (update :media relink-media)
                          (update :colors relink-colors)
                          (d/without-nils))))))

(defn- upsert-file!
  [conn file]
  (let [sql (str "INSERT INTO file (id, project_id, name, revn, version, is_shared, data, created_at, modified_at) "
                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
                 "ON CONFLICT (id) DO UPDATE SET data=?, version=?")]
    (db/exec-one! conn [sql
                        (:id file)
                        (:project-id file)
                        (:name file)
                        (:revn file)
                        (:version file)
                        (:is-shared file)
                        (:data file)
                        (:created-at file)
                        (:modified-at file)
                        (:data file)
                        (:version file)])))

(defn persist-file!
  "Applies all the final validations and perist the file."
  [{:keys [::db/conn ::timestamp] :as cfg} {:keys [id] :as file}]

  (dm/assert!
   "expected valid timestamp"
   (dt/instant? timestamp))

  (let [file   (-> file
                   (assoc :created-at timestamp)
                   (assoc :modified-at timestamp)
                   (assoc :ignore-sync-until (dt/plus timestamp (dt/duration {:seconds 5})))
                   (update :features
                           (fn [features]
                             (let [features (cfeat/check-supported-features! features)]
                               (-> (::features cfg #{})
                                   (set/difference cfeat/frontend-only-features)
                                   (set/union features))))))

        _      (when (contains? cf/flags :file-schema-validation)
                 (fval/validate-file-schema! file))

        _      (when (contains? cf/flags :soft-file-schema-validation)
                 (let [result (ex/try! (fval/validate-file-schema! file))]
                   (when (ex/exception? result)
                     (l/error :hint "file schema validation error" :cause result))))

        file   (if (contains? (:features file) "fdata/objects-map")
                 (feat.fdata/enable-objects-map file)
                 file)

        file   (if (contains? (:features file) "fdata/pointer-map")
                 (binding [pmap/*tracked* (pmap/create-tracked)]
                   (let [file (feat.fdata/enable-pointer-map file)]
                     (feat.fdata/persist-pointers! cfg id)
                     file))
                 file)

        params (-> file
                   (update :features db/encode-pgarray conn "text")
                   (update :data blob/encode))]

    (if (::overwrite cfg)
      (upsert-file! conn params)
      (db/insert! conn :file params ::db/return-keys false))

    file))

(defn apply-pending-migrations!
  "Apply alredy registered pending migrations to files"
  [cfg]
  (doseq [[feature file-id] (-> *state* deref :pending-to-migrate)]
    (case feature
      "components/v2"
      (feat.compv2/migrate-file! cfg file-id
                                 :validate? (::validate cfg true)
                                 :skip-on-graphic-error? true)

      "fdata/shape-data-type"
      nil

      (ex/raise :type :internal
                :code :no-migration-defined
                :hint (str/ffmt "no migation for feature '%' on file importation" feature)
                :feature feature))))
