;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.rpc.commands.binfile
  (:refer-clojure :exclude [assert])
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.pages.migrations :as pmg]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.doc :as doc]
   [app.media :as media]
   [app.rpc.queries.files :as files]
   [app.rpc.queries.projects :as projects]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.tasks.file-gc]
   [app.util.blob :as blob]
   [app.util.bytes :as bs]
   [app.util.fressian :as fres]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [yetti.adapter :as yt])
  (:import
   java.io.DataInputStream
   java.io.DataOutputStream
   java.io.InputStream
   java.io.OutputStream
   java.lang.AutoCloseable))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; VARS & DEFAULTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Threshold in MiB when we pass from using
;; in-memory byte-array's to use temporal files.
(def temp-file-threshold
  (* 1024 1024 2))

;; Represents the current processing file-id on
;; export process.
(def ^:dynamic *file-id*)

;; Stores all media file object references of
;; processed files on import process.
(def ^:dynamic *media*)

;; Stores the objects index on reamping subprocess
;; part of the import process.
(def ^:dynamic *index*)

;; Has the current connection used on the import
;; process.
(def ^:dynamic *conn*)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOW LEVEL STREAM IO API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const buffer-size (:xnio/buffer-size yt/defaults))
(def ^:const penpot-magic-number 800099563638710213)
(def ^:const max-object-size (* 1024 1024 100)) ; Only allow 100MiB max file size.

(def ^:dynamic *position* nil)

(defn get-mark
  [id]
  (case id
    :header  1
    :stream  2
    :uuid    3
    :label   4
    :obj     5
    (ex/raise :type :validation
              :code :invalid-mark-id
              :hint (format "invalid mark id %s" id))))

(defmacro assert
  [expr hint]
  `(when-not ~expr
     (ex/raise :type :validation
               :code :unexpected-condition
               :hint ~hint)))

(defmacro assert-mark
  [v type]
  `(let [expected# (get-mark ~type)
         val#      (long ~v)]
     (when (not= val# expected#)
       (ex/raise :type :validation
                 :code :unexpected-mark
                 :hint (format "received mark %s, expected %s" val# expected#)))))

(defmacro assert-label
  [expr label]
  `(let [v# ~expr]
     (when (not= v# ~label)
       (ex/raise :type :assertion
                 :code :unexpected-label
                 :hint (format "received label %s, expected %s" v# ~label)))))

;; --- PRIMITIVE IO

(defn write-byte!
  [^DataOutputStream output data]
  (l/trace :fn "write-byte!" :data data :position @*position* ::l/async false)
  (.writeByte output (byte data))
  (swap! *position* inc))

(defn read-byte!
  [^DataInputStream input]
  (let [v (.readByte input)]
    (l/trace :fn "read-byte!" :val v :position @*position* ::l/async false)
    (swap! *position* inc)
    v))

(defn write-long!
  [^DataOutputStream output data]
  (l/trace :fn "write-long!" :data data :position @*position* ::l/async false)
  (.writeLong output (long data))
  (swap! *position* + 8))


(defn read-long!
  [^DataInputStream input]
  (let [v (.readLong input)]
    (l/trace :fn "read-long!" :val v :position @*position* ::l/async false)
    (swap! *position* + 8)
    v))

(defn write-bytes!
  [^DataOutputStream output ^bytes data]
  (let [size (alength data)]
    (l/trace :fn "write-bytes!" :size size :position @*position* ::l/async false)
    (.write output data 0 size)
    (swap! *position* + size)))

(defn read-bytes!
  [^InputStream input ^bytes buff]
  (let [size   (alength buff)
        readed (.readNBytes input buff 0 size)]
    (l/trace :fn "read-bytes!" :expected (alength buff) :readed readed :position @*position* ::l/async false)
    (swap! *position* + readed)
    readed))

;; --- COMPOSITE IO

(defn write-uuid!
  [^DataOutputStream output id]
  (l/trace :fn "write-uuid!" :position @*position* :WRITTEN? (.size output) ::l/async false)

  (doto output
    (write-byte! (get-mark :uuid))
    (write-long! (uuid/get-word-high id))
    (write-long! (uuid/get-word-low id))))

(defn read-uuid!
  [^DataInputStream input]
  (l/trace :fn "read-uuid!" :position @*position* ::l/async false)
  (let [m (read-byte! input)]
    (assert-mark m :uuid)
    (let [a (read-long! input)
          b (read-long! input)]
      (uuid/custom a b))))

(defn write-obj!
  [^DataOutputStream output data]
  (l/trace :fn "write-obj!" :position @*position* ::l/async false)
  (let [^bytes data (fres/encode data)]
    (doto output
      (write-byte! (get-mark :obj))
      (write-long! (alength data))
      (write-bytes! data))))

(defn read-obj!
  [^DataInputStream input]
  (l/trace :fn "read-obj!" :position @*position* ::l/async false)
  (let [m (read-byte! input)]
    (assert-mark m :obj)
    (let [size (read-long! input)]
      (assert (pos? size) "incorrect header size found on reading header")
      (let [buff (byte-array size)]
        (read-bytes! input buff)
        (fres/decode buff)))))

(defn write-label!
  [^DataOutputStream output label]
  (l/trace :fn "write-label!" :label label :position @*position* ::l/async false)
  (doto output
    (write-byte! (get-mark :label))
    (write-obj! label)))

(defn read-label!
  [^DataInputStream input]
  (l/trace :fn "read-label!" :position @*position* ::l/async false)
  (let [m (read-byte! input)]
    (assert-mark m :label)
    (read-obj! input)))

(defn write-header!
  [^DataOutputStream output & {:keys [version metadata]}]
  (l/trace :fn "write-header!"
           :version version
           :metadata metadata
           :position @*position*
           ::l/async false)

  (doto output
    (write-byte! (get-mark :header))
    (write-long! penpot-magic-number)
    (write-long! version)
    (write-obj! metadata)))

(defn read-header!
  [^DataInputStream input]
  (l/trace :fn "read-header!" :position @*position* ::l/async false)
  (let [mark  (read-byte! input)
        mnum  (read-long! input)
        vers  (read-long! input)]

    (when (or (not= mark (get-mark :header))
              (not= mnum penpot-magic-number))
      (ex/raise :type :validation
                :code :invalid-penpot-file))

    (-> (read-obj! input)
        (assoc ::version vers))))

(defn copy-stream!
  [^OutputStream output ^InputStream input ^long size]
  (let [written (bs/copy! input output :size size)]
    (l/trace :fn "copy-stream!" :position @*position* :size size :written written ::l/async false)
    (swap! *position* + written)
    written))

(defn write-stream!
  [^DataOutputStream output stream size]
  (l/trace :fn "write-stream!" :position @*position* ::l/async false :size size)
  (doto output
    (write-byte! (get-mark :stream))
    (write-long! size))

  (copy-stream! output stream size))

(defn read-stream!
  [^DataInputStream input]
  (l/trace :fn "read-stream!" :position @*position* ::l/async false)
  (let [m (read-byte! input)
        s (read-long! input)
        p (tmp/tempfile :prefix "penpot.binfile.")]
    (assert-mark m :stream)

    (when (> s max-object-size)
      (ex/raise :type :validation
                :code :max-file-size-reached
                :hint (str/ffmt "unable to import storage object with size % bytes" s)))

    (if (> s temp-file-threshold)
      (with-open [^OutputStream output (io/output-stream p)]
        (let [readed (bs/copy! input output :offset 0 :size s)]
          (l/trace :fn "read-stream*!" :expected s :readed readed :position @*position* ::l/async false)
          (swap! *position* + readed)
          [s p]))
      [s (bs/read-as-bytes input :size s)])))

(defmacro assert-read-label!
  [input expected-label]
  `(let [readed# (read-label! ~input)
         expected# ~expected-label]
     (when (not= readed# expected#)
       (ex/raise :type :validation
                 :code :unexpected-label
                 :hint (format "unxpected label found: %s, expected: %s" readed# expected#)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- HELPERS

(defn- retrieve-file
  [pool file-id]
  (->> (db/query pool :file {:id file-id})
       (map files/decode-row)
       (first)))

(def ^:private sql:file-media-objects
  "SELECT * FROM file_media_object WHERE id = ANY(?)")

(defn- retrieve-file-media
  [pool {:keys [data] :as file}]
  (with-open [^AutoCloseable conn (db/open pool)]
    (let [ids (app.tasks.file-gc/collect-used-media data)
          ids (db/create-array conn "uuid" ids)]
      (db/exec! conn [sql:file-media-objects ids]))))

(def ^:private storage-object-id-xf
  (comp
   (mapcat (juxt :media-id :thumbnail-id))
   (filter uuid?)))

(def ^:private sql:file-libraries
  "WITH RECURSIVE libs AS (
     SELECT fl.id, fl.deleted_at
       FROM file AS fl
       JOIN file_library_rel AS flr ON (flr.library_file_id = fl.id)
      WHERE flr.file_id = ANY(?)
    UNION
     SELECT fl.id, fl.deleted_at
       FROM file AS fl
       JOIN file_library_rel AS flr ON (flr.library_file_id = fl.id)
       JOIN libs AS l ON (flr.file_id = l.id)
   )
   SELECT DISTINCT l.id
     FROM libs AS l
    WHERE l.deleted_at IS NULL OR l.deleted_at > now();")

(defn- retrieve-libraries
  [pool ids]
  (with-open [^AutoCloseable conn (db/open pool)]
    (let [ids (db/create-array conn "uuid" ids)]
      (map :id (db/exec! pool [sql:file-libraries ids])))))

(def ^:private sql:file-library-rels
  "SELECT * FROM file_library_rel
    WHERE file_id = ANY(?)")

(defn- retrieve-library-relations
  [pool ids]
  (with-open [^AutoCloseable conn (db/open pool)]
    (db/exec! conn [sql:file-library-rels (db/create-array conn "uuid" ids)])))

;; --- EXPORT WRITTER

(s/def ::output bs/output-stream?)
(s/def ::file-ids (s/every ::us/uuid :kind vector? :min-count 1))
(s/def ::include-libraries? (s/nilable ::us/boolean))
(s/def ::embed-assets? (s/nilable ::us/boolean))

(s/def ::write-export-options
  (s/keys :req-un [::db/pool ::sto/storage]
          :req    [::output ::file-ids]
          :opt    [::include-libraries? ::embed-assets?]))

(defn write-export!
  "Do the exportation of a speficied file in custom penpot binary
  format. There are some options available for customize the output:

  `::include-libraries?`: additionaly to the specified file, all the
  linked libraries also will be included (including transitive
  dependencies).

  `::embed-assets?`: instead of including the libraryes, embedd in the
  same file library all assets used from external libraries.
  "

  [{:keys [pool storage ::output ::file-ids ::include-libraries? ::embed-assets?] :as options}]

  (us/assert! ::write-export-options options)

  (us/verify!
   :expr (not (and include-libraries? embed-assets?))
   :hint "the `include-libraries?` and `embed-assets?` are mutally excluding options")

  (letfn [(write-header [output files]
            (let [sections [:v1/files :v1/rels :v1/sobjects]
                  mdata    {:penpot-version (:full cf/version)
                            :sections sections
                            :files files}]
              (write-header! output :version 1 :metadata mdata)))

          (write-files [output files sids]
            (l/debug :hint "write section" :section :v1/files :total (count files) ::l/async false)
            (write-label! output :v1/files)
            (doseq [file-id files]
              (let [file  (cond-> (retrieve-file pool file-id)
                            embed-assets? (update :data embed-file-assets file-id))
                    media (retrieve-file-media pool file)]

                ;; Collect all storage ids for later write them all under
                ;; specific storage objects section.
                (vswap! sids into (sequence storage-object-id-xf media))

                (l/trace :hint "write penpot file"
                         :id file-id
                         :media (count media)
                         ::l/async false)

                (doto output
                  (write-obj! file)
                  (write-obj! media)))))

          (write-rels [output files]
            (let [rels (when include-libraries? (retrieve-library-relations pool files))]
              (l/debug :hint "write section" :section :v1/rels :total (count rels) ::l/async false)
              (doto output
                (write-label! :v1/rels)
                (write-obj! rels))))

          (write-sobjects [output sids]
            (l/debug :hint "write section"
                     :section :v1/sobjects
                     :items (count sids)
                     ::l/async false)

            ;; Write all collected storage objects
            (doto output
              (write-label! :v1/sobjects)
              (write-obj! sids))

            (let [storage (media/configure-assets-storage storage)]
              (doseq [id sids]
                (let [{:keys [size] :as obj} @(sto/get-object storage id)]
                  (l/trace :hint "write sobject" :id id ::l/async false)

                  (doto output
                    (write-uuid! id)
                    (write-obj! (meta obj)))

                  (with-open [^InputStream stream @(sto/get-object-data storage obj)]
                    (let [written (write-stream! output stream size)]
                      (when (not= written size)
                        (ex/raise :type :validation
                                  :code :mismatch-readed-size
                                  :hint (str/ffmt "found unexpected object size; size=% written=%" size written)))))))))

          (embed-file-assets [data file-id]
            (binding [*file-id* file-id]
              (let [assets (volatile! [])]
                (walk/postwalk #(cond-> % (map? %) (walk-map-form assets)) data)
                (->> (deref assets)
                     (filter #(as-> (first %) $ (and (uuid? $) (not= $ file-id))))
                     (d/group-by first rest)
                     (reduce process-group-of-assets data)))))

          (walk-map-form [form state]
            (cond
              (uuid? (:fill-color-ref-file form))
              (do
                (vswap! state conj [(:fill-color-ref-file form) :colors (:fill-color-ref-id form)])
                (assoc form :fill-color-ref-file *file-id*))

              (uuid? (:stroke-color-ref-file form))
              (do
                (vswap! state conj [(:stroke-color-ref-file form) :colors (:stroke-color-ref-id form)])
                (assoc form :stroke-color-ref-file *file-id*))

              (uuid? (:typography-ref-file form))
              (do
                (vswap! state conj [(:typography-ref-file form) :typographies (:typography-ref-id form)])
                (assoc form :typography-ref-file *file-id*))

              (uuid? (:component-file form))
              (do
                (vswap! state conj [(:component-file form) :components (:component-id form)])
                (assoc form :component-file *file-id*))

              :else
              form))

          (process-group-of-assets [data [lib-id items]]
            ;; NOTE: there are a posibility that shape refers to a not
            ;; existing file because the file was removed. In this
            ;; case we just ignore the asset.
            (if-let [lib (retrieve-file pool lib-id)]
              (reduce #(process-asset %1 lib %2) data items)
              data))

          (process-asset [data lib [bucket asset-id]]
            (let [asset (get-in lib [:data bucket asset-id])
                  ;; Add a special case for colors that need to have
                  ;; correctly set the :file-id prop (pending of the
                  ;; refactor that will remove it).
                  asset (cond-> asset
                          (= bucket :colors) (assoc :file-id *file-id*))]
              (update data bucket assoc asset-id asset)))]

    (with-open [output (bs/zstd-output-stream output :level 12)]
      (with-open [output (bs/data-output-stream output)]
        (let [libs  (when include-libraries? (retrieve-libraries pool file-ids))
              files (into file-ids libs)
              sids  (volatile! #{})]

          ;; Write header with metadata
          (l/debug :hint "exportation summary"
                   :files (count files)
                   :embed-assets? embed-assets?
                   :include-libs? include-libraries?
                   ::l/async false)

          (write-header output files)
          (write-files output files sids)
          (write-rels output files)
          (write-sobjects output (vec @sids)))))))

(s/def ::project-id ::us/uuid)
(s/def ::input bs/input-stream?)
(s/def ::overwrite? (s/nilable ::us/boolean))
(s/def ::migrate? (s/nilable ::us/boolean))
(s/def ::ignore-index-errors? (s/nilable ::us/boolean))

(s/def ::read-import-options
  (s/keys :req-un [::db/pool ::sto/storage]
          :req    [::project-id ::input]
          :opt    [::overwrite? ::migrate? ::ignore-index-errors?]))

(defn read-import!
  "Do the importation of the specified resource in penpot custom binary
  format. There are some options for customize the importation
  behavior:

  `::overwrite?`: if true, instead of creating new files and remaping id references,
  it reuses all ids and updates existing objects; defaults to `false`.

  `::migrate?`: if true, applies the migration before persisting the
  file data; defaults to `false`.

  `::ignore-index-errors?`: if true, do not fail on index lookup errors, can
  happen with broken files; defaults to: `false`.
  "

  [{:keys [pool storage ::project-id ::timestamp ::input ::overwrite? ::migrate? ::ignore-index-errors?]
    :or {overwrite? false migrate? false timestamp (dt/now)}
    :as options}]

  (us/assert! ::read-import-options options)

  (letfn [(lookup-index [id]
            (if ignore-index-errors?
              (or (get @*index* id) id)
              (let [val (get @*index* id)]
                (l/trace :fn "lookup-index" :id id :val val ::l/async false)
                (when-not val
                  (ex/raise :type :validation
                            :code :incomplete-index
                            :hint "looks like index has missing data"))
                val)))

          (update-index [index coll]
            (loop [items (seq coll)
                   index index]
              (if-let [id (first items)]
                (let [new-id (if overwrite? id (uuid/next))]
                  (l/trace :fn "update-index" :id id :new-id new-id ::l/async false)
                  (recur (rest items)
                         (assoc index id new-id)))
                index)))

          (process-map-form [form]
            (cond-> form
              ;; Relink Image Shapes
              (and (map? (:metadata form))
                   (= :image (:type form)))
              (update-in [:metadata :id] lookup-index)

              ;; This covers old shapes and the new :fills.
              (uuid? (:fill-color-ref-file form))
              (update :fill-color-ref-file lookup-index)

              ;; This covers the old shapes and the new :strokes
              (uuid? (:storage-color-ref-file form))
              (update :stroke-color-ref-file lookup-index)

              ;; This covers all text shapes that have typography referenced
              (uuid? (:typography-ref-file form))
              (update :typography-ref-file lookup-index)

              ;; This covers the shadows and grids (they have directly
              ;; the :file-id prop)
              (uuid? (:file-id form))
              (update :file-id lookup-index)))

          ;; a function responsible to analyze all file data and
          ;; replace the old :component-file reference with the new
          ;; ones, using the provided file-index
          (relink-shapes [data]
            (walk/postwalk (fn [form]
                             (if (map? form)
                               (try
                                 (process-map-form form)
                                 (catch Throwable cause
                                   (l/trace :hint "failed form" :form (pr-str form) ::l/async false)
                                   (throw cause)))
                               form))
                           data))

          ;; A function responsible of process the :media attr of file
          ;; data and remap the old ids with the new ones.
          (relink-media [media]
            (reduce-kv (fn [res k v]
                         (let [id (lookup-index k)]
                           (if (uuid? id)
                             (-> res
                                 (assoc id (assoc v :id id))
                                 (dissoc k))
                             res)))
                       media
                       media))

          (create-or-update-file [params]
            (let [sql (str "INSERT INTO file (id, project_id, name, revn, is_shared, data, created_at, modified_at) "
                           "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                           "ON CONFLICT (id) DO UPDATE SET data=?")]
              (db/exec-one! *conn* [sql
                                    (:id params)
                                    (:project-id params)
                                    (:name params)
                                    (:revn params)
                                    (:is-shared params)
                                    (:data params)
                                    (:created-at params)
                                    (:modified-at params)
                                    (:data params)])))

          (read-files-section! [input expected-files]
            (l/debug :hint "reading section" :section :v1/files ::l/async false)
            (assert-read-label! input :v1/files)

            ;; Process/Read all file
            (doseq [expected-file-id expected-files]
              (let [file    (read-obj! input)
                    media'  (read-obj! input)
                    file-id (:id file)]

                (when (not= file-id expected-file-id)
                  (ex/raise :type :validation
                            :code :inconsistent-penpot-file
                            :hint "the penpot file seems corrupt, found unexpected uuid (file-id)"))


                ;; Update index using with media
                (l/trace :hint "update index with media" ::l/async false)
                (vswap! *index* update-index (map :id media'))

                ;; Store file media for later insertion
                (l/trace :hint "update media references" ::l/async false)
                (vswap! *media* into (map #(update % :id lookup-index)) media')

                (l/trace :hint "procesing file" :file-id file-id ::l/async false)

                (let [file-id' (lookup-index file-id)
                      data     (-> (:data file)
                                   (assoc :id file-id')
                                   (cond-> migrate? (pmg/migrate-data))
                                   (update :pages-index relink-shapes)
                                   (update :components relink-shapes)
                                   (update :media relink-media))

                      params  {:id file-id'
                               :project-id project-id
                               :name (str "Imported: " (:name file))
                               :revn (:revn file)
                               :is-shared (:is-shared file)
                               :data (blob/encode data)
                               :created-at timestamp
                               :modified-at timestamp}]

                  (l/trace :hint "create file" :id file-id' ::l/async false)

                  (if overwrite?
                    (create-or-update-file params)
                    (db/insert! *conn* :file params))

                  (when overwrite?
                    (db/delete! *conn* :file-thumbnail {:file-id file-id'}))))))

          (read-rels-section! [input]
            (l/debug :hint "reading section" :section :v1/rels ::l/async false)
            (assert-read-label! input :v1/rels)

            (let [rels (read-obj! input)]
              ;; Insert all file relations
              (doseq [rel rels]
                (let [rel (-> rel
                              (assoc :synced-at timestamp)
                              (update :file-id lookup-index)
                              (update :library-file-id lookup-index))]
                  (l/trace :hint "create file library link"
                           :file-id (:file-id rel)
                           :lib-id (:library-file-id rel)
                           ::l/async false)
                  (db/insert! *conn* :file-library-rel rel)))))

          (read-sobjects-section! [input]
            (l/debug :hint "reading section" :section :v1/sobjects ::l/async false)
            (assert-read-label! input :v1/sobjects)

            (let [storage (media/configure-assets-storage storage)
                  ids     (read-obj! input)]

              ;; Step 1: process all storage objects
              (doseq [expected-storage-id ids]
                (let [id    (read-uuid! input)
                      mdata (read-obj! input)]

                  (when (not= id expected-storage-id)
                    (ex/raise :type :validation
                              :code :inconsistent-penpot-file
                              :hint "the penpot file seems corrupt, found unexpected uuid (storage-object-id)"))

                  (l/trace :hint "readed storage object" :id id ::l/async false)

                  (let [[size resource] (read-stream! input)
                        hash            (sto/calculate-hash resource)
                        content         (-> (sto/content resource size)
                                            (sto/wrap-with-hash hash))
                        params          (-> mdata
                                            (assoc ::sto/deduplicate? true)
                                            (assoc ::sto/content content)
                                            (assoc ::sto/touched-at (dt/now)))
                        sobject         @(sto/put-object! storage params)]
                    (l/trace :hint "persisted storage object" :id id :new-id (:id sobject) ::l/async false)
                    (vswap! *index* assoc id (:id sobject)))))

              ;; Step 2: insert all file-media-object rows with correct
              ;; storage-id reference.
              (doseq [item @*media*]
                (l/trace :hint "inserting file media objects" :id (:id item) ::l/async false)
                (db/insert! *conn* :file-media-object
                            (-> item
                                (update :file-id lookup-index)
                                (d/update-when :media-id lookup-index)
                                (d/update-when :thumbnail-id lookup-index))
                            {:on-conflict-do-nothing overwrite?}))))]

    (with-open [input (bs/zstd-input-stream input)]
      (with-open [input (bs/data-input-stream input)]
        (db/with-atomic [conn pool]
          (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED;"])

          ;; Verify that we received a proper .penpot file
          (let [{:keys [sections files]} (read-header! input)]
            (l/debug :hint "import verified" :files files :overwrite? overwrite?)
            (binding [*index* (volatile! (update-index {} files))
                      *media* (volatile! [])
                      *conn*  conn]

              (doseq [section sections]
                (case section
                  :v1/rels (read-rels-section! input)
                  :v1/files (read-files-section! input files)
                  :v1/sobjects (read-sobjects-section! input))))))))))

(defn export!
  [cfg]
  (let [path (tmp/tempfile :prefix "penpot.export.")
        id   (uuid/next)
        ts   (dt/now)
        cs   (volatile! nil)]
    (try
      (l/info :hint "start exportation" :export-id id)
      (with-open [output (io/output-stream path)]
        (binding [*position* (atom 0)]
          (write-export! (assoc cfg ::output output))
          path))

      (catch Throwable cause
        (vreset! cs cause)
        (throw cause))

      (finally
        (l/info :hint "exportation finished" :export-id id
                :elapsed (str (inst-ms (dt/diff ts (dt/now))) "ms")
                :cause @cs)))))

(defn import!
  [{:keys [::input] :as cfg}]
  (let [id (uuid/next)
        ts (dt/now)
        cs (volatile! nil)]
    (try
      (l/info :hint "start importation" :import-id id)
      (binding [*position* (atom 0)]
        (with-open [input (io/input-stream input)]
          (read-import! (assoc cfg ::input input))))

      (catch Throwable cause
        (vreset! cs cause)
        (throw cause))

      (finally
        (l/info :hint "importation finished" :import-id id
                :elapsed (str (inst-ms (dt/diff ts (dt/now))) "ms")
                :error? (some? @cs)
                :cause @cs)))))

;; --- Command: export-binfile

(s/def ::file-id ::us/uuid)
(s/def ::profile-id ::us/uuid)
(s/def ::include-libraries? ::us/boolean)
(s/def ::embed-assets? ::us/boolean)

(s/def ::export-binfile
  (s/keys :req-un [::profile-id ::file-id ::include-libraries? ::embed-assets?]))

(sv/defmethod ::export-binfile
  "Export a penpot file in a binary format."
  {::doc/added "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id file-id include-libraries? embed-assets?] :as params}]
  (db/with-atomic [conn pool]
    (files/check-read-permissions! conn profile-id file-id)
    (let [path (export! (assoc cfg
                               ::file-ids [file-id]
                               ::embed-assets? embed-assets?
                               ::include-libraries? include-libraries?))]
      (with-meta {}
        {:transform-response (fn [_ response]
                               (assoc response
                                      :body (io/input-stream path)
                                      :headers {"content-type" "application/octet-stream"}))}))))

(s/def ::file ::media/upload)
(s/def ::import-binfile
  (s/keys :req-un [::profile-id ::project-id ::file]))

(sv/defmethod ::import-binfile
  "Import a penpot file in a binary format."
  {::doc/added "1.15"}
  [{:keys [pool] :as cfg} {:keys [profile-id project-id file] :as params}]
  (db/with-atomic [conn pool]
    (projects/check-read-permissions! conn profile-id project-id)
    (import! (assoc cfg
                    ::input (:path file)
                    ::project-id project-id
                    ::ignore-index-errors? true))))
