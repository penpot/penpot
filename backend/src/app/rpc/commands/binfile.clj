;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.binfile
  (:refer-clojure :exclude [assert])
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.defaults :as cfd]
   [app.common.files.migrations :as pmg]
   [app.common.fressian :as fres]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.features.components-v2 :as features.components-v2]
   [app.features.fdata :as features.fdata]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.media :as media]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.files :as files]
   [app.rpc.commands.projects :as projects]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.tasks.file-gc]
   [app.util.blob :as blob]
   [app.util.pointer-map :as pmap]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.walk :as walk]
   [cuerdas.core :as str]
   [datoteka.io :as io]
   [promesa.util :as pu]
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

(defn- get-files
  [cfg ids]
  (letfn [(get-files* [{:keys [::db/conn]}]
            (let [sql (str "SELECT id FROM file "
                           " WHERE id = ANY(?) ")
                  ids (db/create-array conn "uuid" ids)]
              (->> (db/exec! conn [sql ids])
                   (into [] (map :id))
                   (not-empty))))]

    (db/run! cfg get-files*)))

(defn- get-file
  [cfg file-id]
  (letfn [(get-file* [{:keys [::db/conn]}]
            (binding [pmap/*load-fn* (partial files/load-pointer conn file-id)]
              (some-> (db/get* conn :file {:id file-id} {::db/remove-deleted? false})
                      (files/decode-row)
                      (files/process-pointers deref))))]

    (db/run! cfg get-file*)))

(defn- get-file-media
  [{:keys [::db/pool]} {:keys [data id] :as file}]
  (pu/with-open [conn (db/open pool)]
    (let [ids (app.tasks.file-gc/collect-used-media data)
          ids (db/create-array conn "uuid" ids)
          sql (str "SELECT * FROM file_media_object WHERE id = ANY(?)")]

      ;; We assoc the file-id again to the file-media-object row
      ;; because there are cases that used objects refer to other
      ;; files and we need to ensure in the exportation process that
      ;; all ids matches
      (->> (db/exec! conn [sql ids])
           (mapv #(assoc % :file-id id))))))

(def ^:private storage-object-id-xf
  (comp
   (mapcat (juxt :media-id :thumbnail-id))
   (filter uuid?)))

(def ^:private sql:file-libraries
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

(defn- get-libraries
  [{:keys [::db/pool]} ids]
  (pu/with-open [conn (db/open pool)]
    (let [ids (db/create-array conn "uuid" ids)]
      (map :id (db/exec! pool [sql:file-libraries ids])))))

(defn- get-library-relations
  [cfg ids]
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (let [ids (db/create-array conn "uuid" ids)
                       sql (str "SELECT flr.* FROM file_library_rel AS flr "
                                " WHERE flr.file_id = ANY(?)")]
                   (db/exec! conn [sql ids])))))

(defn- create-or-update-file!
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

(def ^:dynamic *state* nil)
(def ^:dynamic *options* nil)

;; --- EXPORT WRITER

(defn- embed-file-assets
  [data cfg file-id]
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
  (pu/with-open [output (zstd-output-stream output :level 12)
                 output (io/data-output-stream output)]
    (binding [*state* (volatile! {})]
      (run! (fn [section]
              (l/dbg :hint "write section" :section section ::l/sync? true)
              (write-label! output section)
              (let [options (-> options
                                (assoc ::output output)
                                (assoc ::section section))]
                (binding [*options* options]
                  (write-section options))))

            [:v1/metadata :v1/files :v1/rels :v1/sobjects]))))

(defmethod write-section :v1/metadata
  [{:keys [::output ::file-ids ::include-libraries?] :as cfg}]
  (if-let [fids (get-files cfg file-ids)]
    (let [lids (when include-libraries?
                 (get-libraries cfg file-ids))
          ids  (into fids lids)]
      (write-obj! output {:version cf/version :files ids})
      (vswap! *state* assoc :files ids))
    (ex/raise :type :not-found
              :code :files-not-found
              :hint "unable to retrieve files for export")))

(defmethod write-section :v1/files
  [{:keys [::output ::embed-assets? ::include-libraries?] :as cfg}]

  ;; Initialize SIDS with empty vector
  (vswap! *state* assoc :sids [])

  (doseq [file-id (-> *state* deref :files)]
    (let [detach? (and (not embed-assets?) (not include-libraries?))
          file    (cond-> (get-file cfg file-id)
                    detach?
                    (-> (ctf/detach-external-references file-id)
                        (dissoc :libraries))
                    embed-assets?
                    (update :data embed-file-assets cfg file-id))

          media   (get-file-media cfg file)]

      (l/dbg :hint "write penpot file"
             :id file-id
             :name (:name file)
             :features (:features file)
             :media (count media)
             ::l/sync? true)

      (doseq [item media]
        (l/dbg :hint "write penpot file media object" :id (:id item) ::l/sync? true))

      (doto output
        (write-obj! file)
        (write-obj! media))

      (vswap! *state* update :sids into storage-object-id-xf media))))

(defmethod write-section :v1/rels
  [{:keys [::output ::include-libraries?] :as cfg}]
  (let [ids  (-> *state* deref :files)
        rels (when include-libraries?
               (get-library-relations cfg ids))]
    (l/dbg :hint "found rels" :total (count rels) ::l/sync? true)
    (write-obj! output rels)))

(defmethod write-section :v1/sobjects
  [{:keys [::sto/storage ::output]}]
  (let [sids    (-> *state* deref :sids)
        storage (media/configure-assets-storage storage)]

    (l/dbg :hint "found sobjects"
           :items (count sids)
           ::l/sync? true)

    ;; Write all collected storage objects
    (write-obj! output sids)

    (doseq [id sids]
      (let [{:keys [size] :as obj} (sto/get-object storage id)]
        (l/dbg :hint "write sobject" :id id ::l/sync? true)
        (doto output
          (write-uuid! id)
          (write-obj! (meta obj)))

        (pu/with-open [stream (sto/get-object-data storage obj)]
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

(s/def ::profile-id ::us/uuid)
(s/def ::project-id ::us/uuid)
(s/def ::input io/input-stream?)
(s/def ::overwrite? (s/nilable ::us/boolean))
(s/def ::ignore-index-errors? (s/nilable ::us/boolean))

;; FIXME: replace with schema
(s/def ::read-import-options
  (s/keys :req [::db/pool ::sto/storage ::project-id ::profile-id ::input]
          :opt [::overwrite? ::ignore-index-errors?]))

(defn read-import!
  "Do the importation of the specified resource in penpot custom binary
  format. There are some options for customize the importation
  behavior:

  `::overwrite?`: if true, instead of creating new files and remapping id references,
  it reuses all ids and updates existing objects; defaults to `false`.

  `::ignore-index-errors?`: if true, do not fail on index lookup errors, can
  happen with broken files; defaults to: `false`.
  "

  [{:keys [::input ::timestamp] :or {timestamp (dt/now)} :as options}]
  (us/verify! ::read-import-options options)
  (let [version (read-header! input)]
    (read-import (assoc options ::version version ::timestamp timestamp))))

(defn- read-import-v1
  [{:keys [::db/conn ::project-id ::profile-id ::input] :as options}]
  (db/exec-one! conn ["SET idle_in_transaction_session_timeout = 0"])
  (db/exec-one! conn ["SET CONSTRAINTS ALL DEFERRED"])

  (pu/with-open [input (zstd-input-stream input)
                 input (io/data-input-stream input)]
    (binding [*state* (volatile! {:media [] :index {}})]
      (let [team     (teams/get-team options
                                     :profile-id profile-id
                                     :project-id project-id)
            features (cfeat/get-team-enabled-features cf/flags team)]

        ;; Process all sections
        (run! (fn [section]
                (l/dbg :hint "reading section" :section section ::l/sync? true)
                (assert-read-label! input section)
                (let [options (-> options
                                  (assoc ::enabled-features features)
                                  (assoc ::section section)
                                  (assoc ::input input))]
                  (binding [*options* options]
                    (read-section options))))
              [:v1/metadata :v1/files :v1/rels :v1/sobjects])

        ;; Run all pending migrations
        (doseq [[feature file-id] (-> *state* deref :pending-to-migrate)]
          (case feature
            "components/v2"
            (features.components-v2/migrate-file! options file-id)

            "fdata/shape-data-type"
            nil

            ;; "fdata/shape-data-type"
            ;; (features.fdata/enable-objects-map
            (ex/raise :type :internal
                      :code :no-migration-defined
                      :hint (str/ffmt "no migation for feature '%' on file importation" feature)
                      :feature feature)))

        ;; Knowing that the ids of the created files are in index,
        ;; just lookup them and return it as a set
        (let [files (-> *state* deref :files)]
          (into #{} (keep #(get-in @*state* [:index %])) files))))))

(defmethod read-import :v1
  [options]
  (db/tx-run! options read-import-v1))

(defmethod read-section :v1/metadata
  [{:keys [::input]}]
  (let [{:keys [version files]} (read-obj! input)]
    (l/dbg :hint "metadata readed" :version (:full version) :files files ::l/sync? true)
    (vswap! *state* update :index update-index files)
    (vswap! *state* assoc :version version :files files)))

(defn- postprocess-file
  [file]
  (cond-> file
    (and (contains? cfeat/*current* "fdata/objects-map")
         (not (contains? cfeat/*previous* "fdata/objects-map")))
    (features.fdata/enable-objects-map)

    (and (contains? cfeat/*current* "fdata/pointer-map")
         (not (contains? cfeat/*previous* "fdata/pointer-map")))
    (features.fdata/enable-pointer-map)))

(defmethod read-section :v1/files
  [{:keys [::db/conn ::input ::project-id ::enabled-features ::timestamp ::overwrite?]}]

  (doseq [expected-file-id (-> *state* deref :files)]
    (let [file      (read-obj! input)
          media'    (read-obj! input)

          file-id   (:id file)
          file-id'  (lookup-index file-id)

          features  (-> enabled-features
                        (set/difference cfeat/frontend-only-features)
                        (set/union (cfeat/check-supported-features! (:features file))))
          ]

      ;; All features that are enabled and requires explicit migration
      ;; are added to the state for a posterior migration step
      (doseq [feature (-> enabled-features
                          (set/difference cfeat/no-migration-features)
                          (set/difference (:features file)))]
        (vswap! *state* update :pending-to-migrate (fnil conj []) [feature file-id']))

      (when (not= file-id expected-file-id)
        (ex/raise :type :validation
                  :code :inconsistent-penpot-file
                  :found-id file-id
                  :expected-id expected-file-id
                  :hint "the penpot file seems corrupt, found unexpected uuid (file-id)"))

      ;; Update index using with media
      (l/dbg :hint "update index with media" ::l/sync? true)
      (vswap! *state* update :index update-index (map :id media'))

      ;; Store file media for later insertion
      (l/dbg :hint "update media references" ::l/sync? true)
      (vswap! *state* update :media into (map #(update % :id lookup-index)) media')

      (binding [cfeat/*current* features
                cfeat/*previous* (:features file)
                pmap/*tracked* (atom {})]

        (l/dbg :hint "processing file"
               :id file-id
               :features (:features file)
               :version (-> file :data :version)
               ::l/sync? true)

        (let [params (-> file
                         (assoc :id file-id')
                         (assoc :features features)
                         (assoc :project-id project-id)
                         (assoc :created-at timestamp)
                         (assoc :modified-at timestamp)
                         (update :data (fn [data]
                                         (-> data
                                             (assoc :id file-id')
                                             (cond-> (> (:version data) cfd/version)
                                               (assoc :version cfd/version))

                                             ;; FIXME: We're temporarily activating all
                                             ;; migrations because a problem in the
                                             ;; environments messed up with the version
                                             ;; numbers When this problem is fixed delete
                                             ;; the following line
                                             (assoc :version 0)
                                             (update :pages-index relink-shapes)
                                             (update :components relink-shapes)
                                             (update :media relink-media)
                                             (pmg/migrate-data))))
                         (postprocess-file)
                         (update :features #(db/create-array conn "text" %))
                         (update :data blob/encode))]

          (l/dbg :hint "create file" :id file-id' ::l/sync? true)

          (if overwrite?
            (create-or-update-file! conn params)
            (db/insert! conn :file params))

          (files/persist-pointers! conn file-id')

          (when overwrite?
            (db/delete! conn :file-thumbnail {:file-id file-id'}))

          file-id')))))

(defmethod read-section :v1/rels
  [{:keys [::db/conn ::input ::timestamp]}]
  (let [rels (read-obj! input)
        ids  (into #{} (-> *state* deref :files))]
    ;; Insert all file relations
    (doseq [{:keys [library-file-id] :as rel} rels]
      (let [rel (-> rel
                    (assoc :synced-at timestamp)
                    (update :file-id lookup-index)
                    (update :library-file-id lookup-index))]

        (if (contains? ids library-file-id)
          (do
            (l/dbg :hint "create file library link"
                   :file-id (:file-id rel)
                   :lib-id (:library-file-id rel)
                   ::l/sync? true)
            (db/insert! conn :file-library-rel rel))

          (l/warn :hint "ignoring file library link"
                  :file-id (:file-id rel)
                  :lib-id (:library-file-id rel)
                  ::l/sync? true))))))

(defmethod read-section :v1/sobjects
  [{:keys [::sto/storage ::db/conn ::input ::overwrite?]}]
  (let [storage (media/configure-assets-storage storage)
        ids     (read-obj! input)]

    (doseq [expected-storage-id ids]
      (let [id    (read-uuid! input)
            mdata (read-obj! input)]

        (when (not= id expected-storage-id)
          (ex/raise :type :validation
                    :code :inconsistent-penpot-file
                    :hint "the penpot file seems corrupt, found unexpected uuid (storage-object-id)"))

        (l/dbg :hint "readed storage object" :id id ::l/sync? true)

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

          (l/dbg :hint "persisted storage object" :id id :new-id (:id sobject) ::l/sync? true)
          (vswap! *state* update :index assoc id (:id sobject)))))

    (doseq [item (:media @*state*)]
      (l/dbg :hint "inserting file media object"
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
    (l/trc :fn "lookup-index" :id id :val val ::l/sync? true)
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
        (l/trc :fn "update-index" :id id :new-id new-id ::l/sync? true)
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
      (pu/with-open [output (io/output-stream output)]
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
    (pu/with-open [output (io/output-stream path)]
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
        (pu/with-open [input (io/input-stream input)]
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
                              ::profile-id profile-id
                              ::ignore-index-errors? true))]

      (db/update! conn :project
                  {:modified-at (dt/now)}
                  {:id project-id})

      (rph/with-meta ids
        {::audit/props {:file nil :file-ids ids}}))))
