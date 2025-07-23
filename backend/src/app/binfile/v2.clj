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
   [app.binfile.common :as bfc]
   [app.common.data :as d]
   [app.common.features :as cfeat]
   [app.common.logging :as l]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as sql]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.util.events :as events]
   [app.worker :as-alias wrk]
   [clojure.set :as set]
   [cuerdas.core :as str]
   [datoteka.io :as io]
   [promesa.util :as pu])
  (:import
   java.sql.DriverManager))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOW LEVEL API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(declare ^:private write-project!)
(declare ^:private write-file!)

(defn- write-team!
  [cfg team-id]

  (let [team  (bfc/get-team cfg team-id)
        fonts (bfc/get-fonts cfg team-id)]

    (events/tap :progress
                {:op :export
                 :section :write-team
                 :id team-id
                 :name (:name team)})

    (l/trc :hint "write" :obj "team"
           :id (str team-id)
           :fonts (count fonts))

    (when-let [photo-id (:photo-id team)]
      (vswap! bfc/*state* update :storage-objects conj photo-id))

    (vswap! bfc/*state* update :teams conj team-id)
    (vswap! bfc/*state* bfc/collect-storage-objects fonts)

    (write! cfg :team team-id team)

    (doseq [{:keys [id] :as font} fonts]
      (vswap! bfc/*state* update :team-font-variants conj id)
      (write! cfg :team-font-variant id font))))

(defn- write-project!
  [cfg project]
  (events/tap :progress
              {:op :export
               :section :write-project
               :id (:id project)
               :name (:name project)})
  (l/trc :hint "write" :obj "project" :id (str (:id project)))
  (write! cfg :project (str (:id project)) project)
  (vswap! bfc/*state* update :projects conj (:id project)))

(defn- write-file!
  [cfg file-id]
  (let [file   (bfc/get-file cfg file-id)
        thumbs (bfc/get-file-object-thumbnails cfg file-id)
        media  (bfc/get-file-media cfg file)
        rels   (bfc/get-files-rels cfg #{file-id})]

    (events/tap :progress
                {:op :export
                 :section :write-file
                 :id file-id
                 :name (:name file)})

    (vswap! bfc/*state* (fn [state]
                          (-> state
                              (update :files conj file-id)
                              (update :file-media-objects into bfc/xf-map-id media)
                              (bfc/collect-storage-objects thumbs)
                              (bfc/collect-storage-objects media))))

    (write! cfg :file file-id file)
    (write! cfg :file-rels file-id rels)

    (run! (partial write! cfg :file-media-object file-id) media)
    (run! (partial write! cfg :file-object-thumbnail file-id) thumbs)

    (when-let [thumb (bfc/get-file-thumbnail cfg file)]
      (vswap! bfc/*state* bfc/collect-storage-objects [thumb])
      (write! cfg :file-thumbnail file-id thumb))

    (l/trc :hint "write" :obj "file"
           :thumbnails (count thumbs)
           :rels (count rels)
           :media (count media))))

(defn- write-storage-object!
  [{:keys [::sto/storage] :as cfg} id]
  (let [sobj (sto/get-object storage id)
        data (with-open [input (sto/get-object-data storage sobj)]
               (io/read input))]

    (l/trc :hint "write" :obj "storage-object" :id (str id) :size (:size sobj))
    (write! cfg :storage-object id (meta sobj) data)))

(defn- read-storage-object!
  [{:keys [::sto/storage ::bfc/timestamp] :as cfg} id]
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

    (vswap! bfc/*state* update :index assoc id (:id sobject))

    (l/trc :hint "read" :obj "storage-object"
           :id (str id)
           :new-id (str (:id sobject))
           :size (:size sobject))))

(defn read-team!
  [{:keys [::db/conn ::bfc/timestamp] :as cfg} team-id]
  (l/trc :hint "read" :obj "team" :id (str team-id))

  (let [team (read-obj cfg :team team-id)
        team (-> team
                 (update :id bfc/lookup-index)
                 (update :photo-id bfc/lookup-index)
                 (assoc :created-at timestamp)
                 (assoc :modified-at timestamp))]

    (events/tap :progress
                {:op :import
                 :section :read-team
                 :id team-id
                 :name (:name team)})

    (db/insert! conn :team
                (update team :features db/encode-pgarray conn "text")
                ::db/return-keys false)

    (doseq [font (->> (read-seq cfg :team-font-variant)
                      (filter #(= team-id (:team-id %))))]
      (let [font (-> font
                     (update :id bfc/lookup-index)
                     (update :team-id bfc/lookup-index)
                     (update :woff1-file-id bfc/lookup-index)
                     (update :woff2-file-id bfc/lookup-index)
                     (update :ttf-file-id bfc/lookup-index)
                     (update :otf-file-id bfc/lookup-index)
                     (assoc :created-at timestamp)
                     (assoc :modified-at timestamp))]
        (db/insert! conn :team-font-variant font
                    ::db/return-keys false)))

    team))

(defn read-project!
  [{:keys [::db/conn ::bfc/timestamp] :as cfg} project-id]
  (l/trc :hint "read" :obj "project" :id (str project-id))

  (let [project (read-obj cfg :project project-id)
        project (-> project
                    (update :id bfc/lookup-index)
                    (update :team-id bfc/lookup-index)
                    (assoc :created-at timestamp)
                    (assoc :modified-at timestamp))]

    (events/tap :progress
                {:op :import
                 :section :read-project
                 :id project-id
                 :name (:name project)})

    (db/insert! conn :project project
                ::db/return-keys false)))

(defn read-file!
  [{:keys [::db/conn ::bfc/timestamp] :as cfg} file-id]
  (l/trc :hint "read" :obj "file" :id (str file-id))

  (let [file (-> (read-obj cfg :file file-id)
                 (update :id bfc/lookup-index)
                 (update :project-id bfc/lookup-index))
        file (bfc/process-file cfg file)]

    (events/tap :progress
                {:op :import
                 :section :read-file
                 :id file-id
                 :name (:name file)})

    ;; All features that are enabled and requires explicit migration are
    ;; added to the state for a posterior migration step.
    (doseq [feature (-> (::bfc/features cfg)
                        (set/difference cfeat/no-migration-features)
                        (set/difference (:features file)))]
      (vswap! bfc/*state* update :pending-to-migrate (fnil conj []) [feature (:id file)]))

    (bfc/save-file! cfg file ::db/return-keys false))

  (doseq [thumbnail (read-seq cfg :file-object-thumbnail file-id)]
    (let [thumbnail (-> thumbnail
                        (update :file-id bfc/lookup-index)
                        (update :media-id bfc/lookup-index))
          file-id    (:file-id thumbnail)

          thumbnail (update thumbnail :object-id
                            #(str/replace-first % #"^(.*?)/" (str file-id "/")))]

      (db/insert! conn :file-tagged-object-thumbnail thumbnail
                  {::db/return-keys false})))

  (doseq [rel (read-obj cfg :file-rels file-id)]
    (let [rel (-> rel
                  (update :file-id bfc/lookup-index)
                  (update :library-file-id bfc/lookup-index)
                  (assoc :synced-at timestamp))]
      (db/insert! conn :file-library-rel rel
                  ::db/return-keys false)))

  (doseq [media (read-seq cfg :file-media-object file-id)]
    (let [media (-> media
                    (update :id bfc/lookup-index)
                    (update :file-id bfc/lookup-index)
                    (update :media-id bfc/lookup-index)
                    (update :thumbnail-id bfc/lookup-index))]
      (db/insert! conn :file-media-object media
                  ::db/return-keys false
                  ::sql/on-conflict-do-nothing true))))

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
        tp  (ct/tpoint)
        cfg (create-database cfg)]

    (l/inf :hint "start"
           :operation "export"
           :id (str id)
           :path (str (::path cfg)))

    (try
      (db/tx-run! cfg (fn [cfg]
                        (setup-schema! cfg)
                        (binding [bfc/*state* (volatile! empty-summary)]
                          (write-team! cfg team-id)

                          (run! (partial write-project! cfg)
                                (bfc/get-team-projects cfg team-id))

                          (run! (partial write-file! cfg)
                                (bfc/get-team-files-ids cfg team-id))

                          (run! (partial write-storage-object! cfg)
                                (-> bfc/*state* deref :storage-objects))

                          (write! cfg :manifest "team-id" team-id)
                          (write! cfg :manifest "objects" (deref bfc/*state*))

                          (::path cfg))))
      (finally
        (pu/close! (::db cfg))

        (let [elapsed (tp)]
          (l/inf :hint "end"
                 :operation "export"
                 :id (str id)
                 :elapsed (ct/format-duration elapsed)))))))

(defn import-team!
  [cfg path]
  (let [id  (uuid/next)
        tp  (ct/tpoint)

        cfg (-> (create-database cfg path)
                (assoc ::bfc/timestamp (ct/now)))]

    (l/inf :hint "start"
           :operation "import"
           :id (str id)
           :path (str (::path cfg)))

    (try
      (db/tx-run! cfg (fn [{:keys [::db/conn] :as cfg}]
                        (db/exec-one! conn ["SET idle_in_transaction_session_timeout = 0"])
                        (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])

                        (binding [bfc/*state* (volatile! {:index {}})]
                          (let [objects (read-obj cfg :manifest "objects")]

                            ;; We first process all storage objects, they have
                            ;; deduplication so we can't rely on simple reindex. This
                            ;; operation populates the index for all storage objects.
                            (run! (partial read-storage-object! cfg) (:storage-objects objects))

                            ;; Populate index with all the incoming objects
                            (vswap! bfc/*state* update :index
                                    (fn [index]
                                      (-> index
                                          (bfc/update-index (:teams objects))
                                          (bfc/update-index (:projects objects))
                                          (bfc/update-index (:files objects))
                                          (bfc/update-index (:file-media-objects objects))
                                          (bfc/update-index (:team-font-variants objects)))))

                            (let [team-id  (read-obj cfg :manifest "team-id")
                                  team     (read-team! cfg team-id)
                                  features (cfeat/get-team-enabled-features cf/flags team)
                                  cfg      (assoc cfg ::bfc/features features)]

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
                 :elapsed (ct/format-duration elapsed)))))))
