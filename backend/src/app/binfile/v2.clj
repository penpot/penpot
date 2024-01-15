;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.binfile.v2
  "A sqlite3 based binary file exportation with support for exportation
  of entire team (or multiple teams) at once."
  (:refer-clojure :exclude [read])
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.defaults :as cfd]
   [app.common.files.migrations :as fmg]
   [app.common.files.validate :as fval]
   [app.common.logging :as l]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.features.fdata :as feat.fdata]
   [app.http.sse :as sse]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.media :as media]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.time :as dt]
   [app.worker :as-alias wrk]
   [clojure.set :as set]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [datoteka.io :as io]
   [promesa.util :as pu])
  (:import
   java.sql.DriverManager))

(set! *warn-on-reflection* true)

(def ^:dynamic *state* nil)
(def ^:dynamic *options* nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOW LEVEL API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- lookup-index
  [id]
  (when-let [val (get-in @*state* [:index id])]
    (l/trc :fn "lookup-index" :id (some-> id str) :result (some-> val str) ::l/sync? true)
    (or val id)))

(defn- index-object
  [index obj & attrs]
  (reduce (fn [index attr-fn]
            (let [old-id (attr-fn obj)
                  new-id (uuid/next)]
              (assoc index old-id new-id)))
          index
          attrs))

(defn- update-index
  ([index coll]
   (update-index index coll identity))
  ([index coll attr]
   (reduce #(index-object %1 %2 attr) index coll)))

(defn- create-database
  ([cfg]
   (let [path (tmp/tempfile :prefix "penpot.binfile." :suffix ".sqlite")]
     (create-database cfg path)))
  ([cfg path]
   (let [db (DriverManager/getConnection (str "jdbc:sqlite:" path))]
     (assoc cfg ::db db ::path path))))

(def ^:private
  sql:create-kvdata-table
  "CREATE TABLE kvdata (
     tag text NOT NULL,
     key text NOT NULL,
     val text NOT NULL,
     dat blob NULL
   )")

(def ^:private
  sql:create-kvdata-index
  "CREATE INDEX kvdata__tag_key__idx
      ON kvdata (tag, key)")

(defn- decode-row
  [{:keys [data features] :as row}]
  (cond-> row
    features (assoc :features (db/decode-pgarray features #{}))
    data     (assoc :data (blob/decode data))))

(defn- setup-schema!
  [{:keys [::db]}]
  (db/exec-one! db [sql:create-kvdata-table])
  (db/exec-one! db [sql:create-kvdata-index]))

(defn- write!
  [{:keys [::db]} tag k v & [data]]
  (db/insert! db :kvdata
              {:tag (d/name tag)
               :key (str k)
               :val (t/encode-str v {:type :json-verbose})
               :dat data}
              {::db/return-keys false}))

(defn- read-blob
  [{:keys [::db]} tag k]
  (let [obj (db/get db :kvdata
                    {:tag (d/name tag)
                     :key (str k)}
                    {::sql/columns [:dat]})]
    (:dat obj)))

(defn- read-seq
  ([{:keys [::db]} tag]
   (->> (db/query db :kvdata
                  {:tag (d/name tag)}
                  {::sql/columns [::val]})
        (map :val)
        (map t/decode-str)))
  ([{:keys [::db]} tag k]
   (->> (db/query db :kvdata
                  {:tag (d/name tag)
                   :key (str k)}
                  {::sql/columns [::val]})
        (map :val)
        (map t/decode-str))))

(defn- read-obj
  [{:keys [::db]} tag k]
  (let [obj (db/get db :kvdata
                    {:tag (d/name tag)
                     :key (str k)}
                    {::sql/columns [:val]})]
    (-> obj :val t/decode-str)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMPORT/EXPORT IMPL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private xf-map-id
  (map :id))

(def ^:private xf-map-media-id
  (comp
   (mapcat (juxt :media-id
                 :thumbnail-id
                 :woff1-file-id
                 :woff2-file-id
                 :ttf-file-id
                 :otf-file-id))
   (filter uuid?)))

;; NOTE: Will be used in future, commented for satisfy linter
;; (def ^:private sql:get-libraries
;;   "WITH RECURSIVE libs AS (
;;      SELECT fl.id
;;        FROM file AS fl
;;        JOIN file_library_rel AS flr ON (flr.library_file_id = fl.id)
;;       WHERE flr.file_id = ANY(?)
;;     UNION
;;      SELECT fl.id
;;        FROM file AS fl
;;        JOIN file_library_rel AS flr ON (flr.library_file_id = fl.id)
;;        JOIN libs AS l ON (flr.file_id = l.id)
;;    )
;;    SELECT DISTINCT l.id
;;      FROM libs AS l")
;;
;; (defn- get-libraries
;;   "Get all libraries ids related to provided file ids"
;;   [{:keys [::db/conn]} ids]
;;   (let [ids' (db/create-array conn "uuid" ids)]
;;     (->> (db/exec! conn [sql:get-libraries ids])
;;          (into #{} xf-map-id))))
;;
;; (def ^:private sql:get-project-files
;;   "SELECT f.id FROM file AS f
;;     WHERE f.project_id = ?")

;; (defn- get-project-files
;;   "Get a set of file ids for the project"
;;   [{:keys [::db/conn]} project-id]
;;   (->> (db/exec! conn [sql:get-project-files project-id])
;;        (into #{} xf-map-id)))

(def ^:private sql:get-team-files
  "SELECT f.id FROM file AS f
     JOIN project AS p ON (p.id = f.project_id)
    WHERE p.team_id = ?")

(defn- get-team-files
  "Get a set of file ids for the specified team-id"
  [{:keys [::db/conn]} team-id]
  (->> (db/exec! conn [sql:get-team-files team-id])
       (into #{} xf-map-id)))

(def ^:private sql:get-team-projects
  "SELECT p.id FROM project AS p
    WHERE p.team_id = ?")

(defn- get-team-projects
  "Get a set of project ids for the team"
  [{:keys [::db/conn]} team-id]
  (->> (db/exec! conn [sql:get-team-projects team-id])
       (into #{} xf-map-id)))

(declare ^:private write-project!)
(declare ^:private write-file!)

(defn- write-team!
  [{:keys [::db/conn] :as cfg} team-id]

  (sse/tap {:type :export-progress
            :section :write-team
            :id team-id})

  (let [team  (db/get conn :team {:id team-id}
                      ::db/remove-deleted false
                      ::db/check-deleted false)
        team  (decode-row team)
        fonts (db/query conn :team-font-variant
                        {:team-id team-id
                         :deleted-at nil}
                        {::sql/for-share true})]

    (l/trc :hint "write" :obj "team"
           :id (str team-id)
           :fonts (count fonts))

    (vswap! *state* update :teams conj team-id)
    (vswap! *state* update :storage-objects into xf-map-media-id fonts)

    (write! cfg :team team-id team)

    (doseq [{:keys [id] :as font} fonts]
      (vswap! *state* update :team-font-variants conj id)
      (write! cfg :team-font-variant id font))))

(defn- write-project!
  [{:keys [::db/conn] :as cfg} project-id]

  (sse/tap {:type :export-progress
            :section :write-project
            :id project-id})

  (let [project (db/get conn :project {:id project-id}
                        ::db/remove-deleted false
                        ::db/check-deleted false)]

    (l/trc :hint "write" :obj "project" :id (str project-id))
    (write! cfg :project (str project-id) project)

    (vswap! *state* update :projects conj project-id)))

(defn- write-file!
  [{:keys [::db/conn] :as cfg} file-id]

  (sse/tap {:type :export-progress
            :section :write-file
            :id file-id})

  (let [file   (binding [pmap/*load-fn* (partial feat.fdata/load-pointer cfg file-id)]
                 (-> (db/get conn :file {:id file-id}
                             ::sql/for-share true
                             ::db/remove-deleted false
                             ::db/check-deleted false)
                     (decode-row)
                     (update :data feat.fdata/process-pointers deref)
                     (update :data feat.fdata/process-objects (partial into {}))))

        thumbs (db/query conn :file-tagged-object-thumbnail
                         {:file-id file-id
                          :deleted-at nil}
                         {::sql/for-share true})

        media  (db/query conn :file-media-object
                         {:file-id file-id
                          :deleted-at nil}
                         {::sql/for-share true})

        rels   (db/query conn :file-library-rel
                         {:file-id file-id})]

    (vswap! *state* (fn [state]
                      (-> state
                          (update :files conj file-id)
                          (update :file-media-objects into (map :id) media)
                          (update :storage-objects into xf-map-media-id thumbs)
                          (update :storage-objects into xf-map-media-id media))))

    (write! cfg :file file-id file)
    (write! cfg :file-rels file-id rels)

    (run! (partial write! cfg :file-media-object file-id) media)
    (run! (partial write! cfg :file-object-thumbnail file-id) thumbs)

    (when-let [thumb (db/get* conn :file-thumbnail
                              {:file-id file-id
                               :revn (:revn file)
                               :data nil}
                              {::sql/for-share true
                               ::sql/columns [:media-id :file-id :revn]})]
      (vswap! *state* update :storage-objects into xf-map-media-id [thumb])
      (write! cfg :file-thumbnail file-id thumb))

    (l/trc :hint "write" :obj "file"
           :thumbnails (count thumbs)
           :rels (count rels)
           :media (count media))))

(defn- write-storage-object!
  [{:keys [::sto/storage] :as cfg} id]
  (let [sobj (sto/get-object storage id)
        data (with-open [input (sto/get-object-data storage sobj)]
               (io/read-as-bytes input))]

    (l/trc :hint "write" :obj "storage-object" :id (str id) :size (:size sobj))
    (write! cfg :storage-object id (meta sobj) data)))

(defn- read-storage-object!
  [{:keys [::sto/storage ::timestamp] :as cfg} id]
  (let [mdata   (read-obj cfg :storage-object id)
        data    (read-blob cfg :storage-object id)
        hash    (sto/calculate-hash data)

        content (-> (sto/content data)
                    (sto/wrap-with-hash hash))

        params  (-> mdata
                    (assoc ::sto/content content)
                    (assoc ::sto/deduplicate? true)
                    (assoc ::sto/touched-at timestamp))

        sobject  (sto/put-object! storage params)]

    (vswap! *state* update :index assoc id (:id sobject))

    (l/trc :hint "read" :obj "storage-object"
           :id (str id)
           :new-id (str (:id sobject))
           :size (:size sobject))))

(defn read-team!
  [{:keys [::db/conn ::timestamp] :as cfg} team-id]
  (l/trc :hint "read" :obj "team" :id (str team-id))

  (sse/tap {:type :import-progress
            :section :read-team
            :id team-id})

  (let [team (read-obj cfg :team team-id)
        team (-> team
                 (update :id lookup-index)
                 (update :photo-id lookup-index)
                 (assoc :created-at timestamp)
                 (assoc :modified-at timestamp))]

    (db/insert! conn :team
                (update team :features db/encode-pgarray conn "text")
                ::db/return-keys false)

    (doseq [font (->> (read-seq cfg :team-font-variant)
                      (filter #(= team-id (:team-id %))))]
      (let [font (-> font
                     (update :id lookup-index)
                     (update :team-id lookup-index)
                     (update :woff1-file-id lookup-index)
                     (update :woff2-file-id lookup-index)
                     (update :ttf-file-id lookup-index)
                     (update :otf-file-id lookup-index)
                     (assoc :created-at timestamp)
                     (assoc :modified-at timestamp))]
        (db/insert! conn :team-font-variant font
                    ::db/return-keys false)))

    team))

(defn read-project!
  [{:keys [::db/conn ::timestamp] :as cfg} project-id]
  (l/trc :hint "read" :obj "project" :id (str project-id))

  (sse/tap {:type :import-progress
            :section :read-project
            :id project-id})

  (let [project (read-obj cfg :project project-id)
        project (-> project
                    (update :id lookup-index)
                    (update :team-id lookup-index)
                    (assoc :created-at timestamp)
                    (assoc :modified-at timestamp))]

    (db/insert! conn :project project
                ::db/return-keys false)))

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
              (uuid? (:storage-color-ref-file form))
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
              (update :file-id lookup-index)))]

    (walk/postwalk (fn [form]
                     (if (map? form)
                       (try
                         (process-map-form form)
                         (catch Throwable cause
                           (l/warn :hint "failed form" :form (pr-str form) ::l/sync? true)
                           (throw cause)))
                       form))
                   data)))

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

(defn- process-file
  [{:keys [id] :as file}]
  (-> file
      (update :data (fn [fdata]
                      (-> fdata
                          (assoc :id id)
                          (dissoc :recent-colors)
                          (cond-> (> (:version fdata) cfd/version)
                            (assoc :version cfd/version))
                          ;; FIXME: We're temporarily activating all
                          ;; migrations because a problem in the
                          ;; environments messed up with the version
                          ;; numbers When this problem is fixed delete
                          ;; the following line
                          (cond-> (> (:version fdata) 22)
                            (assoc :version 22)))))
      (fmg/migrate-file)
      (update :data (fn [fdata]
                      (-> fdata
                          (update :pages-index relink-shapes)
                          (update :components relink-shapes)
                          (update :media relink-media)
                          (update :colors relink-colors)
                          (d/without-nils))))))

(defn read-file!
  [{:keys [::db/conn ::timestamp] :as cfg} file-id]
  (l/trc :hint "read" :obj "file" :id (str file-id))

  (sse/tap {:type :import-progress
            :section :read-file
            :id file-id})

  (let [file (read-obj cfg :file file-id)

        file (-> file
                 (update :id lookup-index)
                 (process-file))

        ;; All features that are enabled and requires explicit migration are
        ;; added to the state for a posterior migration step.
        _    (doseq [feature (-> (::features cfg)
                                 (set/difference cfeat/no-migration-features)
                                 (set/difference (:features file)))]
               (vswap! *state* update :pending-to-migrate (fnil conj []) [feature (:id file)]))


        file (-> file
                 (update :project-id lookup-index))

        file (-> file
                 (assoc :created-at timestamp)
                 (assoc :modified-at timestamp)
                 (update :features
                         (fn [features]
                           (let [features (cfeat/check-supported-features! features)]
                             (-> (::features cfg)
                                 (set/difference cfeat/frontend-only-features)
                                 (set/union features))))))

        _    (when (contains? cf/flags :file-schema-validation)
               (fval/validate-file-schema! file))

        _    (when (contains? cf/flags :soft-file-schema-validation)
               (let [result (ex/try! (fval/validate-file-schema! file))]
                 (when (ex/exception? result)
                   (l/error :hint "file schema validation error" :cause result))))

        file (if (contains? (:features file) "fdata/objects-map")
               (feat.fdata/enable-objects-map file)
               file)

        file (if (contains? (:features file) "fdata/pointer-map")
               (binding [pmap/*tracked* (pmap/create-tracked)]
                 (let [file (feat.fdata/enable-pointer-map file)]
                   (feat.fdata/persist-pointers! cfg (:id file))
                   file))
               file)]

    (db/insert! conn :file
                (-> file
                    (update :features db/encode-pgarray conn "text")
                    (update :data blob/encode))
                {::db/return-keys false}))

  (doseq [thumbnail (read-seq cfg :file-object-thumbnail file-id)]
    (let [thumbnail (-> thumbnail
                        (update :file-id lookup-index)
                        (update :media-id lookup-index))
          file-id    (:file-id thumbnail)

          thumbnail (update thumbnail :object-id
                            #(str/replace-first % #"^(.*?)/" (str file-id "/")))]

      (db/insert! conn :file-tagged-object-thumbnail thumbnail
                  {::db/return-keys false})))

  (doseq [rel (read-obj cfg :file-rels file-id)]
    (let [rel (-> rel
                  (update :file-id lookup-index)
                  (update :library-file-id lookup-index)
                  (assoc :synced-at timestamp))]
      (db/insert! conn :file-library-rel rel
                  ::db/return-keys false)))

  (doseq [media (read-seq cfg :file-media-object file-id)]
    (let [media (-> media
                    (update :id lookup-index)
                    (update :file-id lookup-index)
                    (update :media-id lookup-index)
                    (update :thumbnail-id lookup-index))]
      (db/insert! conn :file-media-object media
                  ::db/return-keys false))))

(def ^:private empty-summary
  {:teams #{}
   :files #{}
   :projects #{}
   :file-media-objects #{}
   :team-font-variants #{}
   :storage-objects #{}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PUBLIC API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn export-team!
  [cfg team-id]
  (let [id  (uuid/next)
        tp  (dt/tpoint)

        cfg (-> (create-database cfg)
                (update ::sto/storage media/configure-assets-storage))]

    (l/inf :hint "start"
           :operation "export"
           :id (str id)
           :path (str (::path cfg)))

    (try
      (db/tx-run! cfg (fn [cfg]
                        (setup-schema! cfg)
                        (binding [*state* (volatile! empty-summary)]
                          (write-team! cfg team-id)

                          (run! (partial write-project! cfg)
                                (get-team-projects cfg team-id))

                          (run! (partial write-file! cfg)
                                (get-team-files cfg team-id))

                          (run! (partial write-storage-object! cfg)
                                (-> *state* deref :storage-objects))

                          (write! cfg :manifest "team-id" team-id)
                          (write! cfg :manifest "objects" (deref *state*))

                          (::path cfg))))
      (finally
        (pu/close! (::db cfg))

        (let [elapsed (tp)]
          (l/inf :hint "end"
                 :operation "export"
                 :id (str id)
                 :elapsed (dt/format-duration elapsed)))))))

;; NOTE: will be used in future, commented for satisfy linter
;; (defn- run-pending-migrations!
;;   [cfg]
;;   ;; Run all pending migrations
;;   (doseq [[feature file-id] (-> *state* deref :pending-to-migrate)]
;;     (case feature
;;       "components/v2"
;;       (feat.compv2/migrate-file! cfg file-id :validate? (::validate cfg true))
;;       (ex/raise :type :internal
;;                 :code :no-migration-defined
;;                 :hint (str/ffmt "no migation for feature '%' on file importation" feature)
;;                 :feature feature))))

(defn import-team!
  [cfg path]
  (let [id  (uuid/next)
        tp  (dt/tpoint)

        cfg (-> (create-database cfg path)
                (update ::sto/storage media/configure-assets-storage)
                (assoc ::timestamp (dt/now)))]

    (l/inf :hint "start"
           :operation "import"
           :id (str id)
           :path (str (::path cfg)))

    (try
      (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                        (db/exec-one! conn ["SET idle_in_transaction_session_timeout = 0"])
                        (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])

                        (binding [*state* (volatile! {:index {}})]
                          (let [objects (read-obj cfg :manifest "objects")]

                            ;; We first process all storage objects, they have
                            ;; deduplication so we can't rely on simple reindex. This
                            ;; operation populates the index for all storage objects.
                            (run! (partial read-storage-object! cfg) (:storage-objects objects))

                            ;; Populate index with all the incoming objects
                            (vswap! *state* update :index
                                    (fn [index]
                                      (-> index
                                          (update-index (:teams objects))
                                          (update-index (:projects objects))
                                          (update-index (:files objects))
                                          (update-index (:file-media-objects objects))
                                          (update-index (:team-font-variants objects)))))

                            (let [team-id  (read-obj cfg :manifest "team-id")
                                  team     (read-team! cfg team-id)
                                  features (cfeat/get-team-enabled-features cf/flags team)
                                  cfg      (assoc cfg ::features features)]

                              (run! (partial read-project! cfg) (:projects objects))
                              (run! (partial read-file! cfg) (:files objects))

                              ;; (run-pending-migrations! cfg)

                              team)))))
      (finally
        (pu/close! (::db cfg))

        (let [elapsed (tp)]
          (l/inf :hint "end"
                 :operation "import"
                 :id (str id)
                 :elapsed (dt/format-duration elapsed)))))))
