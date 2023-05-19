;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.binfile
  (:refer-clojure :exclude [assert])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.files.features :as ffeat]
   [app.common.fressian :as fres]
   [app.common.logging :as l]
   [app.common.pages.migrations :as pmg]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.projects :as projects]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.tasks.file-gc]
   [app.util.blob :as blob]
   [app.util.objects-map :as omap]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [datoteka.io :as io]
   [yetti.adapter :as yt]
   [yetti.response :as yrs])
  (:import
   com.github.luben.zstd.ZstdInputStream
   com.github.luben.zstd.ZstdOutputStream
   java.io.DataInputStream
   java.io.DataOutputStream
   java.io.InputStream
   java.io.OutputStream))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEFAULTS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Threshold in MiB when we pass from using
;; in-memory byte-array's to use temporal files.
(def temp-file-threshold
  (* 1024 1024 2))

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
  (l/trace :fn "write-byte!" :data data :position @*position* ::l/sync? true)
  (.writeByte output (byte data))
  (swap! *position* inc))

(defn read-byte!
  [^DataInputStream input]
  (let [v (.readByte input)]
    (l/trace :fn "read-byte!" :val v :position @*position* ::l/sync? true)
    (swap! *position* inc)
    v))

(defn write-long!
  [^DataOutputStream output data]
  (l/trace :fn "write-long!" :data data :position @*position* ::l/sync? true)
  (.writeLong output (long data))
  (swap! *position* + 8))


(defn read-long!
  [^DataInputStream input]
  (let [v (.readLong input)]
    (l/trace :fn "read-long!" :val v :position @*position* ::l/sync? true)
    (swap! *position* + 8)
    v))

(defn write-bytes!
  [^DataOutputStream output ^bytes data]
  (let [size (alength data)]
    (l/trace :fn "write-bytes!" :size size :position @*position* ::l/sync? true)
    (.write output data 0 size)
    (swap! *position* + size)))

(defn read-bytes!
  [^InputStream input ^bytes buff]
  (let [size   (alength buff)
        readed (.readNBytes input buff 0 size)]
    (l/trace :fn "read-bytes!" :expected (alength buff) :readed readed :position @*position* ::l/sync? true)
    (swap! *position* + readed)
    readed))

;; --- COMPOSITE IO

(defn write-uuid!
  [^DataOutputStream output id]
  (l/trace :fn "write-uuid!" :position @*position* :WRITTEN? (.size output) ::l/sync? true)

  (doto output
    (write-byte! (get-mark :uuid))
    (write-long! (uuid/get-word-high id))
    (write-long! (uuid/get-word-low id))))

(defn read-uuid!
  [^DataInputStream input]
  (l/trace :fn "read-uuid!" :position @*position* ::l/sync? true)
  (let [m (read-byte! input)]
    (assert-mark m :uuid)
    (let [a (read-long! input)
          b (read-long! input)]
      (uuid/custom a b))))

(defn write-obj!
  [^DataOutputStream output data]
  (l/trace :fn "write-obj!" :position @*position* ::l/sync? true)
  (let [^bytes data (fres/encode data)]
    (doto output
      (write-byte! (get-mark :obj))
      (write-long! (alength data))
      (write-bytes! data))))

(defn read-obj!
  [^DataInputStream input]
  (l/trace :fn "read-obj!" :position @*position* ::l/sync? true)
  (let [m (read-byte! input)]
    (assert-mark m :obj)
    (let [size (read-long! input)]
      (assert (pos? size) "incorrect header size found on reading header")
      (let [buff (byte-array size)]
        (read-bytes! input buff)
        (fres/decode buff)))))

(defn write-label!
  [^DataOutputStream output label]
  (l/trace :fn "write-label!" :label label :position @*position* ::l/sync? true)
  (doto output
    (write-byte! (get-mark :label))
    (write-obj! label)))

(defn read-label!
  [^DataInputStream input]
  (l/trace :fn "read-label!" :position @*position* ::l/sync? true)
  (let [m (read-byte! input)]
    (assert-mark m :label)
    (read-obj! input)))

(defn write-header!
  [^OutputStream output version]
  (l/trace :fn "write-header!"
           :version version
           :position @*position*
           ::l/sync? true)
  (let [vers   (-> version name (subs 1) parse-long)
        output (io/data-output-stream output)]
    (doto output
      (write-byte! (get-mark :header))
      (write-long! penpot-magic-number)
      (write-long! vers))))

(defn read-header!
  [^InputStream input]
  (l/trace :fn "read-header!" :position @*position* ::l/sync? true)
  (let [input (io/data-input-stream input)
        mark  (read-byte! input)
        mnum  (read-long! input)
        vers  (read-long! input)]

    (when (or (not= mark (get-mark :header))
              (not= mnum penpot-magic-number))
      (ex/raise :type :validation
                :code :invalid-penpot-file
                :hint "invalid penpot file"))

    (keyword (str "v" vers))))

(defn copy-stream!
  [^OutputStream output ^InputStream input ^long size]
  (let [written (io/copy! input output :size size)]
    (l/trace :fn "copy-stream!" :position @*position* :size size :written written ::l/sync? true)
    (swap! *position* + written)
    written))

(defn write-stream!
  [^DataOutputStream output stream size]
  (l/trace :fn "write-stream!" :position @*position* ::l/sync? true :size size)
  (doto output
    (write-byte! (get-mark :stream))
    (write-long! size))

  (copy-stream! output stream size))

(defn read-stream!
  [^DataInputStream input]
  (l/trace :fn "read-stream!" :position @*position* ::l/sync? true)
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
        (let [readed (io/copy! input output :offset 0 :size s)]
          (l/trace :fn "read-stream*!" :expected s :readed readed :position @*position* ::l/sync? true)
          (swap! *position* + readed)
          [s p]))
      [s (io/read-as-bytes input :size s)])))

(defmacro assert-read-label!
  [input expected-label]
  `(let [readed# (read-label! ~input)
         expected# ~expected-label]
     (when (not= readed# expected#)
       (ex/raise :type :validation
                 :code :unexpected-label
                 :hint (format "unexpected label found: %s, expected: %s" readed# expected#)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; --- HELPERS

(defn zstd-input-stream
  ^InputStream
  [input]
  (ZstdInputStream. ^InputStream input))

(defn zstd-output-stream
  ^OutputStream
  [output & {:keys [level] :or {level 0}}]
  (ZstdOutputStream. ^OutputStream output (int level)))

(defn- retrieve-file
  [pool file-id]
  (dm/with-open [conn (db/open pool)]
    (binding [pmap/*load-fn* (partial files/load-pointer conn file-id)]
      (some-> (db/get* conn :file {:id file-id})
              (files/decode-row)
              (files/process-pointers deref)))))

(def ^:private sql:file-media-objects
  "SELECT * FROM file_media_object WHERE id = ANY(?)")

(defn- retrieve-file-media
  [pool {:keys [data id] :as file}]
  (dm/with-open [conn (db/open pool)]
    (let [ids (app.tasks.file-gc/collect-used-media data)
          ids (db/create-array conn "uuid" ids)]

      ;; We assoc the file-id again to the file-media-object row
      ;; because there are cases that used objects refer to other
      ;; files and we need to ensure in the exportation process that
      ;; all ids matches
      (->> (db/exec! conn [sql:file-media-objects ids])
           (mapv #(assoc % :file-id id))))))

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
  (dm/with-open [conn (db/open pool)]
    (let [ids (db/create-array conn "uuid" ids)]
      (map :id (db/exec! pool [sql:file-libraries ids])))))

(def ^:private sql:file-library-rels
  "SELECT * FROM file_library_rel
    WHERE file_id = ANY(?)")

(defn- retrieve-library-relations
  [pool ids]
  (dm/with-open [conn (db/open pool)]
    (db/exec! conn [sql:file-library-rels (db/create-array conn "uuid" ids)])))

(defn- create-or-update-file
  [conn params]
  (let [sql (str "INSERT INTO file (id, project_id, name, revn, is_shared, data, created_at, modified_at) "
                 "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                 "ON CONFLICT (id) DO UPDATE SET data=?")]
    (db/exec-one! conn [sql
                        (:id params)
                        (:project-id params)
                        (:name params)
                        (:revn params)
                        (:is-shared params)
                        (:data params)
                        (:created-at params)
                        (:modified-at params)
                        (:data params)])))

;; --- GENERAL PURPOSE DYNAMIC VARS

(def ^:dynamic *state*)
(def ^:dynamic *options*)

;; --- EXPORT WRITER

(defn- embed-file-assets
  [data conn file-id]
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
            (if-let [lib (retrieve-file conn lib-id)]
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

(defmulti write-export ::version)
(defmulti write-section ::section)

(s/def ::output io/output-stream?)
(s/def ::file-ids (s/every ::us/uuid :kind vector? :min-count 1))
(s/def ::include-libraries? (s/nilable ::us/boolean))
(s/def ::embed-assets? (s/nilable ::us/boolean))

(s/def ::write-export-options
  (s/keys :req [::db/pool ::sto/storage ::output ::file-ids]
          :opt [::include-libraries? ::embed-assets?]))

(defn write-export!
  "Do the exportation of a specified file in custom penpot binary
  format. There are some options available for customize the output:

  `::include-libraries?`: additionally to the specified file, all the
  linked libraries also will be included (including transitive
  dependencies).

  `::embed-assets?`: instead of including the libraries, embed in the
  same file library all assets used from external libraries."
  [{:keys [::include-libraries? ::embed-assets?] :as options}]

  (us/assert! ::write-export-options options)
  (us/verify!
   :expr (not (and include-libraries? embed-assets?))
   :hint "the `include-libraries?` and `embed-assets?` are mutally excluding options")
  (write-export options))

(defmethod write-export :default
  [{:keys [::output] :as options}]
  (write-header! output :v1)
  (with-open [output (zstd-output-stream output :level 12)]
    (with-open [output (io/data-output-stream output)]
      (binding [*state* (volatile! {})]
        (run! (fn [section]
                (l/debug :hint "write section" :section section ::l/sync? true)
                (write-label! output section)
                (let [options (-> options
                                  (assoc ::output output)
                                  (assoc ::section section))]
                  (binding [*options* options]
                    (write-section options))))

              [:v1/metadata :v1/files :v1/rels :v1/sobjects])))))

(defmethod write-section :v1/metadata
  [{:keys [::db/pool ::output ::file-ids ::include-libraries?]}]
  (let [libs  (when include-libraries?
                (retrieve-libraries pool file-ids))
        files (into file-ids libs)]
    (write-obj! output {:version cf/version :files files})
    (vswap! *state* assoc :files files)))

(defmethod write-section :v1/files
  [{:keys [::db/pool ::output ::embed-assets?]}]

  ;; Initialize SIDS with empty vector
  (vswap! *state* assoc :sids [])

  (doseq [file-id (-> *state* deref :files)]
    (let [file  (cond-> (retrieve-file pool file-id)
                  embed-assets?
                  (update :data embed-file-assets pool file-id))

          media (retrieve-file-media pool file)]

      (l/debug :hint "write penpot file"
               :id file-id
               :media (count media)
               ::l/sync? true)

      (doto output
        (write-obj! file)
        (write-obj! media))

      (vswap! *state* update :sids into storage-object-id-xf media))))

(defmethod write-section :v1/rels
  [{:keys [::db/pool ::output ::include-libraries?]}]
  (let [rels  (when include-libraries?
                (retrieve-library-relations pool (-> *state* deref :files)))]
    (l/debug :hint "found rels" :total (count rels) ::l/sync? true)
    (write-obj! output rels)))

(defmethod write-section :v1/sobjects
  [{:keys [::sto/storage ::output]}]
  (let [sids    (-> *state* deref :sids)
        storage (media/configure-assets-storage storage)]
    (l/debug :hint "found sobjects"
             :items (count sids)
             ::l/sync? true)

    ;; Write all collected storage objects
    (write-obj! output sids)

    (doseq [id sids]
      (let [{:keys [size] :as obj} (sto/get-object storage id)]
        (l/debug :hint "write sobject" :id id ::l/sync? true)
        (doto output
          (write-uuid! id)
          (write-obj! (meta obj)))

        (with-open [^InputStream stream (sto/get-object-data storage obj)]
          (let [written (write-stream! output stream size)]
            (when (not= written size)
              (ex/raise :type :validation
                        :code :mismatch-readed-size
                        :hint (str/ffmt "found unexpected object size; size=% written=%" size written)))))))))

;; --- EXPORT READER

(declare lookup-index)
(declare update-index)
(declare relink-media)
(declare relink-shapes)

(defmulti read-import ::version)
(defmulti read-section ::section)

(s/def ::project-id ::us/uuid)
(s/def ::input io/input-stream?)
(s/def ::overwrite? (s/nilable ::us/boolean))
(s/def ::migrate? (s/nilable ::us/boolean))
(s/def ::ignore-index-errors? (s/nilable ::us/boolean))

(s/def ::read-import-options
  (s/keys :req [::db/pool ::sto/storage ::project-id ::input]
          :opt [::overwrite? ::migrate? ::ignore-index-errors?]))

(defn read-import!
  "Do the importation of the specified resource in penpot custom binary
  format. There are some options for customize the importation
  behavior:

  `::overwrite?`: if true, instead of creating new files and remapping id references,
  it reuses all ids and updates existing objects; defaults to `false`.

  `::migrate?`: if true, applies the migration before persisting the
  file data; defaults to `false`.

  `::ignore-index-errors?`: if true, do not fail on index lookup errors, can
  happen with broken files; defaults to: `false`.
  "

  [{:keys [::input ::timestamp] :or {timestamp (dt/now)} :as options}]
  (us/verify! ::read-import-options options)
  (let [version (read-header! input)]
    (read-import (assoc options ::version version ::timestamp timestamp))))

(defmethod read-import :v1
  [{:keys [::db/pool ::input] :as options}]
  (with-open [input (zstd-input-stream input)]
    (with-open [input (io/data-input-stream input)]
      (db/with-atomic [conn pool]
        (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED;"])
        (binding [*state* (volatile! {:media [] :index {}})]
          (run! (fn [section]
                  (l/debug :hint "reading section" :section section ::l/sync? true)
                  (assert-read-label! input section)
                  (let [options (-> options
                                    (assoc ::section section)
                                    (assoc ::input input)
                                    (assoc :conn conn))]
                    (binding [*options* options]
                      (read-section options))))
                [:v1/metadata :v1/files :v1/rels :v1/sobjects])

          ;; Knowing that the ids of the created files are in
          ;; index, just lookup them and return it as a set
          (let [files (-> *state* deref :files)]
            (into #{} (keep #(get-in @*state* [:index %])) files)))))))

(defmethod read-section :v1/metadata
  [{:keys [::input]}]
  (let [{:keys [version files]} (read-obj! input)]
    (l/debug :hint "metadata readed" :version (:full version) :files files ::l/sync? true)
    (vswap! *state* update :index update-index files)
    (vswap! *state* assoc :version version :files files)))

(defn- postprocess-file
  [data]
  (let [omap-wrap ffeat/*wrap-with-objects-map-fn*
        pmap-wrap ffeat/*wrap-with-pointer-map-fn*]
    (-> data
        (update :pages-index update-vals #(update % :objects omap-wrap))
        (update :pages-index update-vals pmap-wrap)
        (update :components update-vals #(d/update-when % :objects omap-wrap))
        (update :components pmap-wrap))))

(defmethod read-section :v1/files
  [{:keys [conn ::input ::migrate? ::project-id ::timestamp ::overwrite?]}]
  (doseq [expected-file-id (-> *state* deref :files)]
    (let [file     (read-obj! input)
          media'   (read-obj! input)
          file-id  (:id file)
          features (files/get-default-features)]

      (when (not= file-id expected-file-id)
        (ex/raise :type :validation
                  :code :inconsistent-penpot-file
                  :hint "the penpot file seems corrupt, found unexpected uuid (file-id)"))

      ;; Update index using with media
      (l/debug :hint "update index with media" ::l/sync? true)
      (vswap! *state* update :index update-index (map :id media'))

      ;; Store file media for later insertion
      (l/debug :hint "update media references" ::l/sync? true)
      (vswap! *state* update :media into (map #(update % :id lookup-index)) media')

      (l/debug :hint "processing file" :file-id file-id ::features features ::l/sync? true)

      (binding [ffeat/*current* features
                ffeat/*wrap-with-objects-map-fn* (if (features "storage/objects-map") omap/wrap identity)
                ffeat/*wrap-with-pointer-map-fn* (if (features "storage/pointer-map") pmap/wrap identity)
                pmap/*tracked* (atom {})]

        (let [file-id' (lookup-index file-id)
              data     (-> (:data file)
                           (assoc :id file-id')
                           (cond-> migrate? (pmg/migrate-data))
                           (update :pages-index relink-shapes)
                           (update :components relink-shapes)
                           (update :media relink-media)
                           (postprocess-file))

              params  {:id file-id'
                       :project-id project-id
                       :features (db/create-array conn "text" features)
                       :name (:name file)
                       :revn (:revn file)
                       :is-shared (:is-shared file)
                       :data (blob/encode data)
                       :created-at timestamp
                       :modified-at timestamp}]

          (l/debug :hint "create file" :id file-id' ::l/sync? true)

          (if overwrite?
            (create-or-update-file conn params)
            (db/insert! conn :file params))

          (files/persist-pointers! conn file-id')

          (when overwrite?
            (db/delete! conn :file-thumbnail {:file-id file-id'})))))))

(defmethod read-section :v1/rels
  [{:keys [conn ::input ::timestamp]}]
  (let [rels (read-obj! input)]
    ;; Insert all file relations
    (doseq [rel rels]
      (let [rel (-> rel
                    (assoc :synced-at timestamp)
                    (update :file-id lookup-index)
                    (update :library-file-id lookup-index))]
        (l/debug :hint "create file library link"
                 :file-id (:file-id rel)
                 :lib-id (:library-file-id rel)
                 ::l/sync? true)
        (db/insert! conn :file-library-rel rel)))))

(defmethod read-section :v1/sobjects
  [{:keys [::sto/storage conn ::input ::overwrite?]}]
  (let [storage (media/configure-assets-storage storage)
        ids     (read-obj! input)]

    (doseq [expected-storage-id ids]
      (let [id    (read-uuid! input)
            mdata (read-obj! input)]

        (when (not= id expected-storage-id)
          (ex/raise :type :validation
                    :code :inconsistent-penpot-file
                    :hint "the penpot file seems corrupt, found unexpected uuid (storage-object-id)"))

        (l/debug :hint "readed storage object" :id id ::l/sync? true)

        (let [[size resource] (read-stream! input)
              hash            (sto/calculate-hash resource)
              content         (-> (sto/content resource size)
                                  (sto/wrap-with-hash hash))
              params          (-> mdata
                                  (assoc ::sto/deduplicate? true)
                                  (assoc ::sto/content content)
                                  (assoc ::sto/touched-at (dt/now))
                                  (assoc :bucket "file-media-object"))

              sobject         (sto/put-object! storage params)]

          (l/debug :hint "persisted storage object" :id id :new-id (:id sobject) ::l/sync? true)
          (vswap! *state* update :index assoc id (:id sobject)))))

    (doseq [item (:media @*state*)]
      (l/debug :hint "inserting file media object"
               :id (:id item)
               :file-id (:file-id item)
               ::l/sync? true)

      (let [file-id (lookup-index (:file-id item))]
        (if (= file-id (:file-id item))
          (l/warn :hint "ignoring file media object" :file-id (:file-id item) ::l/sync? true)
          (db/insert! conn :file-media-object
                      (-> item
                          (assoc :file-id file-id)
                          (d/update-when :media-id lookup-index)
                          (d/update-when :thumbnail-id lookup-index))
                      {:on-conflict-do-nothing overwrite?}))))))

(defn- lookup-index
  [id]
  (let [val (get-in @*state* [:index id])]
    (l/trace :fn "lookup-index" :id id :val val ::l/sync? true)
    (when (and (not (::ignore-index-errors? *options*)) (not val))
      (ex/raise :type :validation
                :code :incomplete-index
                :hint "looks like index has missing data"))
    (or val id)))

(defn- update-index
  [index coll]
  (loop [items (seq coll)
         index index]
    (if-let [id (first items)]
      (let [new-id (if (::overwrite? *options*) id (uuid/next))]
        (l/trace :fn "update-index" :id id :new-id new-id ::l/sync? true)
        (recur (rest items)
               (assoc index id new-id)))
      index)))

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
              (and (map? (:fill-image form))
                   (= :path (:type form)))
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HIGH LEVEL API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn export!
  [cfg output]
  (let [id (uuid/next)
        tp (dt/tpoint)
        ab (volatile! false)
        cs (volatile! nil)]
    (try
      (l/info :hint "start exportation" :export-id id)
      (dm/with-open [output (io/output-stream output)]
        (binding [*position* (atom 0)]
          (write-export! (assoc cfg ::output output))))

      (catch java.io.IOException _cause
        ;; Do nothing, EOF means client closes connection abruptly
        (vreset! ab true)
        nil)

      (catch Throwable cause
        (vreset! cs cause)
        (vreset! ab true)
        (throw cause))

      (finally
        (l/info :hint "exportation finished" :export-id id
                :elapsed (str (inst-ms (tp)) "ms")
                :aborted @ab
                :cause @cs)))))

(defn export-to-tmpfile!
  [cfg]
  (let [path (tmp/tempfile :prefix "penpot.export.")]
    (dm/with-open [output (io/output-stream path)]
      (export! cfg output)
      path)))

(defn import!
  [{:keys [::input] :as cfg}]
  (let [id (uuid/next)
        tp (dt/tpoint)
        cs (volatile! nil)]
    (l/info :hint "import: started" :import-id id)
    (try
      (binding [*position* (atom 0)]
        (dm/with-open [input (io/input-stream input)]
          (read-import! (assoc cfg ::input input))))

      (catch Throwable cause
        (vreset! cs cause)
        (throw cause))

      (finally
        (l/info :hint "import: terminated"
                :import-id id
                :elapsed (dt/format-duration (tp))
                :error? (some? @cs)
                :cause @cs
                )))))

;; --- Command: export-binfile

(s/def ::file-id ::us/uuid)
(s/def ::include-libraries? ::us/boolean)
(s/def ::embed-assets? ::us/boolean)

(s/def ::export-binfile
  (s/keys :req [::rpc/profile-id]
          :req-un [::file-id ::include-libraries? ::embed-assets?]))

(sv/defmethod ::export-binfile
  "Export a penpot file in a binary format."
  {::doc/added "1.15"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id file-id include-libraries? embed-assets?] :as params}]
  (files/check-read-permissions! pool profile-id file-id)
  (let [body (reify yrs/StreamableResponseBody
               (-write-body-to-stream [_ _ output-stream]
                 (-> cfg
                     (assoc ::file-ids [file-id])
                     (assoc ::embed-assets? embed-assets?)
                     (assoc ::include-libraries? include-libraries?)
                     (export! output-stream))))]

    (fn [_]
      {::yrs/status 200
       ::yrs/body body
       ::yrs/headers {"content-type" "application/octet-stream"}})))

(s/def ::file ::media/upload)
(s/def ::import-binfile
  (s/keys :req [::rpc/profile-id]
          :req-un [::project-id ::file]))

(sv/defmethod ::import-binfile
  "Import a penpot file in a binary format."
  {::doc/added "1.15"
   ::webhooks/event? true}
  [{:keys [::db/pool] :as cfg} {:keys [::rpc/profile-id project-id file] :as params}]
  (db/with-atomic [conn pool]
    (projects/check-read-permissions! conn profile-id project-id)
    (let [ids (import! (assoc cfg
                              ::input (:path file)
                              ::project-id project-id
                              ::ignore-index-errors? true))]
      (rph/with-meta ids
        {::audit/props {:file nil :file-ids ids}}))))
