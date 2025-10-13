;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.binfile.v1
  "A custom, perfromance and efficiency focused binfile format impl"
  (:refer-clojure :exclude [assert])
  (:require
   [app.binfile.common :as bfc]
   [app.binfile.migrations :as bfm]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.fressian :as fres]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.time :as ct]
   [app.common.types.file :as ctf]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.loggers.audit :as-alias audit]
   [app.loggers.webhooks :as-alias webhooks]
   [app.rpc :as-alias rpc]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.storage :as sto]
   [app.storage.tmp :as tmp]
   [app.tasks.file-gc]
   [app.util.events :as events]
   [app.worker :as-alias wrk]
   [clojure.java.io :as jio]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [datoteka.io :as io]
   [promesa.util :as pu]
   [yetti.adapter :as yt])
  (:import
   com.github.luben.zstd.ZstdIOException
   com.github.luben.zstd.ZstdInputStream
   com.github.luben.zstd.ZstdOutputStream
   java.io.DataInputStream
   java.io.DataOutputStream
   java.io.InputStream
   java.io.OutputStream))

(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOW LEVEL STREAM IO API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const buffer-size (:xnio/buffer-size yt/defaults))
(def ^:const penpot-magic-number 800099563638710213)

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
  (let [written (io/copy input output :size size)]
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

    (when (> s bfc/max-object-size)
      (ex/raise :type :validation
                :code :max-file-size-reached
                :hint (str/ffmt "unable to import storage object with size % bytes" s)))

    (if (> s bfc/temp-file-threshold)
      (with-open [^OutputStream output (io/output-stream p)]
        (let [readed (io/copy input output :offset 0 :size s)]
          (l/trace :fn "read-stream*!" :expected s :readed readed :position @*position* ::l/sync? true)
          (swap! *position* + readed)
          [s p]))
      [s (io/read input :size s)])))

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
  (db/run! cfg (fn [{:keys [::db/conn]}]
                 (let [sql (str "SELECT id FROM file "
                                " WHERE id = ANY(?) ")
                       ids (db/create-array conn "uuid" ids)]
                   (->> (db/exec! conn [sql ids])
                        (into [] (map :id))
                        (not-empty))))))

;; --- EXPORT WRITER

(defmulti write-export ::version)
(defmulti write-section ::section)

(defn write-export!
  [{:keys [::bfc/include-libraries ::bfc/embed-assets] :as cfg}]
  (when (and include-libraries embed-assets)
    (throw (IllegalArgumentException.
            "the `include-libraries` and `embed-assets` are mutally excluding options")))

  (write-export cfg))

(defmethod write-export :default
  [{:keys [::output] :as options}]
  (write-header! output :v1)
  (pu/with-open [output (zstd-output-stream output :level 12)
                 output (io/data-output-stream output)]
    (binding [bfc/*state* (volatile! {})]
      (run! (fn [section]
              (l/dbg :hint "write section" :section section ::l/sync? true)
              (write-label! output section)
              (let [options (-> options
                                (assoc ::output output)
                                (assoc ::section section))]
                (binding [bfc/*options* options]
                  (write-section options))))

            [:v1/metadata :v1/files :v1/rels :v1/sobjects]))))

(defmethod write-section :v1/metadata
  [{:keys [::output ::bfc/ids ::bfc/include-libraries] :as cfg}]
  (if-let [fids (get-files cfg ids)]
    (let [lids (when include-libraries
                 (bfc/get-libraries cfg ids))
          ids  (into fids lids)]
      (write-obj! output {:version cf/version :files ids})
      (vswap! bfc/*state* assoc :files ids))
    (ex/raise :type :not-found
              :code :files-not-found
              :hint "unable to retrieve files for export")))

(defmethod write-section :v1/files
  [{:keys [::output ::bfc/embed-assets ::bfc/include-libraries] :as cfg}]

  ;; Initialize SIDS with empty vector
  (vswap! bfc/*state* assoc :sids [])

  (doseq [file-id (-> bfc/*state* deref :files)]
    (let [detach?    (and (not embed-assets) (not include-libraries))
          thumbnails (->> (bfc/get-file-object-thumbnails cfg file-id)
                          (mapv #(dissoc % :file-id)))

          file       (cond-> (bfc/get-file cfg file-id :realize? true)
                       detach?
                       (-> (ctf/detach-external-references file-id)
                           (dissoc :libraries))

                       embed-assets
                       (update :data #(bfc/embed-assets cfg % file-id))

                       :always
                       (assoc :thumbnails thumbnails))

          media      (bfc/get-file-media cfg file)]

      (l/dbg :hint "write penpot file"
             :id (str file-id)
             :name (:name file)
             :thumbnails (count thumbnails)
             :features (:features file)
             :media (count media)
             ::l/sync? true)

      (doseq [item media]
        (l/dbg :hint "write penpot file media object"
               :id (:id item) ::l/sync? true))

      (doseq [item thumbnails]
        (l/dbg :hint "write penpot file object thumbnail"
               :media-id (str (:media-id item)) ::l/sync? true))

      (doto output
        (write-obj! file)
        (write-obj! media))

      (vswap! bfc/*state* update :sids into bfc/xf-map-media-id media)
      (vswap! bfc/*state* update :sids into bfc/xf-map-media-id thumbnails))))

(defmethod write-section :v1/rels
  [{:keys [::output ::bfc/include-libraries] :as cfg}]
  (let [ids  (-> bfc/*state* deref :files set)
        rels (when include-libraries
               (bfc/get-files-rels cfg ids))]
    (l/dbg :hint "found rels" :total (count rels) ::l/sync? true)
    (write-obj! output rels)))

(defmethod write-section :v1/sobjects
  [{:keys [::output] :as cfg}]
  (let [sids    (-> bfc/*state* deref :sids)
        storage (sto/resolve cfg)]

    (l/dbg :hint "found sobjects"
           :items (count sids)
           ::l/sync? true)

    ;; Write all collected storage objects
    (write-obj! output sids)

    (doseq [id sids]
      (let [{:keys [size] :as obj} (sto/get-object storage id)]
        (l/dbg :hint "write sobject" :id (str id) ::l/sync? true)

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

(defmulti read-import ::version)
(defmulti read-section ::section)

(s/def ::bfc/profile-id ::us/uuid)
(s/def ::bfc/project-id ::us/uuid)
(s/def ::bfc/input io/input-stream?)
(s/def ::ignore-index-errors? (s/nilable ::us/boolean))

(s/def ::read-import-options
  (s/keys :req [::db/pool ::sto/storage ::bfc/project-id ::bfc/profile-id ::bfc/input]
          :opt [::ignore-index-errors?]))

(defn read-import!
  "Do the importation of the specified resource in penpot custom binary
  format."
  [{:keys [::bfc/input ::bfc/timestamp] :or {timestamp (ct/now)} :as options}]

  (dm/assert!
   "expected input stream"
   (io/input-stream? input))

  (dm/assert!
   "expected valid instant"
   (ct/inst? timestamp))

  (let [version (read-header! input)]
    (read-import (assoc options ::version version ::bfc/timestamp timestamp))))

(defn- read-import-v1
  [{:keys [::db/conn ::bfc/project-id ::bfc/profile-id ::bfc/input] :as cfg}]

  (bfc/disable-database-timeouts! cfg)

  (pu/with-open [input (zstd-input-stream input)
                 input (io/data-input-stream input)]
    (binding [bfc/*state* (volatile! {:media [] :index {}})]
      (let [team      (teams/get-team conn
                                      :profile-id profile-id
                                      :project-id project-id)

            features  (cfeat/get-team-enabled-features cf/flags team)]

        ;; Process all sections
        (run! (fn [section]
                (l/dbg :hint "reading section" :section section ::l/sync? true)
                (assert-read-label! input section)
                (let [options (-> cfg
                                  (assoc ::bfc/features features)
                                  (assoc ::section section)
                                  (assoc ::bfc/input input))]
                  (binding [bfc/*options* options]
                    (events/tap :progress {:op :import :section section})
                    (read-section options))))
              [:v1/metadata :v1/files :v1/rels :v1/sobjects])

        (bfm/apply-pending-migrations! cfg)

        ;; Knowing that the ids of the created files are in index,
        ;; just lookup them and return it as a set
        (let [files (-> bfc/*state* deref :files)]
          (into #{} (keep #(get-in @bfc/*state* [:index %])) files))))))

(defmethod read-import :v1
  [options]
  (db/tx-run! options read-import-v1))

(defmethod read-section :v1/metadata
  [{:keys [::bfc/input]}]
  (let [{:keys [version files]} (read-obj! input)]
    (l/dbg :hint "metadata readed"
           :version (:full version)
           :files (mapv str files)
           ::l/sync? true)
    (vswap! bfc/*state* update :index bfc/update-index files)
    (vswap! bfc/*state* assoc :version version :files files)))

(defn- remap-thumbnails
  [thumbnails file-id]
  (mapv (fn [thumbnail]
          (-> thumbnail
              (assoc :file-id file-id)
              (update :object-id #(str/replace-first % #"^(.*?)/" (str file-id "/")))))
        thumbnails))

(defmethod read-section :v1/files
  [{:keys [::bfc/input ::bfc/project-id ::bfc/name] :as system}]
  (doseq [[idx expected-file-id] (d/enumerate (-> bfc/*state* deref :files))]
    (let [file       (read-obj! input)
          media      (read-obj! input)

          file-id    (:id file)
          file-id'   (bfc/lookup-index file-id)

          file       (bfc/clean-file-features file)
          thumbnails (:thumbnails file)]

      (when (not= file-id expected-file-id)
        (ex/raise :type :validation
                  :code :inconsistent-penpot-file
                  :found-id file-id
                  :expected-id expected-file-id
                  :hint "the penpot file seems corrupt, found unexpected uuid (file-id)"))

      (l/dbg :hint "processing file"
             :id (str file-id)
             :features (:features file)
             :version (-> file :data :version)
             :media (count media)
             :thumbnails (count thumbnails)
             ::l/sync? true)

      (when (seq thumbnails)
        (let [thumbnails (remap-thumbnails thumbnails file-id')]
          (l/dbg :hint "updated index with thumbnails"
                 :total (count thumbnails)
                 ::l/sync? true)
          (vswap! bfc/*state* update :thumbnails bfc/into-vec thumbnails)))

      (when (seq media)
        ;; Update index with media
        (l/dbg :hint "update index with media" :total (count media) ::l/sync? true)
        (vswap! bfc/*state* update :index bfc/update-index (map :id media))

        ;; Store file media for later insertion
        (l/dbg :hint "update media references" ::l/sync? true)
        (vswap! bfc/*state* update :media into (map #(update % :id bfc/lookup-index)) media))

      (let [file (-> file
                     (assoc :id file-id')
                     (cond-> (and (= idx 0) (some? name))
                       (assoc :name name))
                     (assoc :project-id project-id)
                     (dissoc :thumbnails))
            file  (bfc/process-file system file)]

        ;; All features that are enabled and requires explicit migration are
        ;; added to the state for a posterior migration step.
        (doseq [feature (-> (::bfc/features system)
                            (set/difference cfeat/no-migration-features)
                            (set/difference (:features file)))]
          (vswap! bfc/*state* update :pending-to-migrate (fnil conj []) [feature file-id']))

        (l/dbg :hint "create file" :id (str file-id') ::l/sync? true)
        (bfc/save-file! system file ::db/return-keys false)

        file-id'))))

(defmethod read-section :v1/rels
  [{:keys [::db/conn ::bfc/input ::bfc/timestamp]}]
  (let [rels (read-obj! input)
        ids  (into #{} (-> bfc/*state* deref :files))]
    ;; Insert all file relations
    (doseq [{:keys [library-file-id] :as rel} rels]
      (let [rel (-> rel
                    (assoc :synced-at timestamp)
                    (update :file-id bfc/lookup-index)
                    (update :library-file-id bfc/lookup-index))]

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
  [{:keys [::db/conn ::bfc/input ::bfc/timestamp] :as cfg}]
  (let [storage (sto/resolve cfg)
        ids     (read-obj! input)
        thumb?  (into #{} (map :media-id) (:thumbnails @bfc/*state*))]

    (doseq [expected-storage-id ids]
      (let [id    (read-uuid! input)
            mdata (read-obj! input)]

        (when (not= id expected-storage-id)
          (ex/raise :type :validation
                    :code :inconsistent-penpot-file
                    :hint "the penpot file seems corrupt, found unexpected uuid (storage-object-id)"))

        (l/dbg :hint "readed storage object" :id (str id) ::l/sync? true)

        (let [[size resource] (read-stream! input)
              hash            (sto/calculate-hash resource)
              content         (-> (sto/content resource size)
                                  (sto/wrap-with-hash hash))

              params          (-> mdata
                                  (assoc ::sto/content content)
                                  (assoc ::sto/deduplicate? true)
                                  (assoc ::sto/touched-at timestamp))

              params          (if (thumb? id)
                                (assoc params :bucket "file-object-thumbnail")
                                (assoc params :bucket "file-media-object"))

              sobject         (sto/put-object! storage params)]

          (l/dbg :hint "persisted storage object"
                 :old-id (str id)
                 :new-id (str (:id sobject))
                 :is-thumbnail (boolean (thumb? id))
                 ::l/sync? true)

          (vswap! bfc/*state* update :index assoc id (:id sobject)))))

    (doseq [item (:media @bfc/*state*)]
      (l/dbg :hint "inserting file media object"
             :id (str (:id item))
             :file-id (str (:file-id item))
             ::l/sync? true)

      (let [file-id (bfc/lookup-index (:file-id item))]
        (if (= file-id (:file-id item))
          (l/warn :hint "ignoring file media object" :file-id (str file-id) ::l/sync? true)
          (db/insert! conn :file-media-object
                      (-> item
                          (assoc :file-id file-id)
                          (d/update-when :media-id bfc/lookup-index)
                          (d/update-when :thumbnail-id bfc/lookup-index))))))

    (doseq [item (:thumbnails @bfc/*state*)]
      (let [item (update item :media-id bfc/lookup-index)]
        (l/dbg :hint "inserting file object thumbnail"
               :file-id (str (:file-id item))
               :media-id (str (:media-id item))
               :object-id (:object-id item)
               ::l/sync? true)
        (db/insert! conn :file-tagged-object-thumbnail item)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HIGH LEVEL API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn export-files!
  "Do the exportation of a specified file in custom penpot binary
  format. There are some options available for customize the output:

  `::bfc/include-libraries`: additionally to the specified file, all the
  linked libraries also will be included (including transitive
  dependencies).

  `::bfc/embed-assets`: instead of including the libraries, embed in the
  same file library all assets used from external libraries."

  [{:keys [::bfc/ids] :as cfg} output]

  (dm/assert!
   "expected a set of uuid's for `::bfc/ids` parameter"
   (and (set? ids)
        (every? uuid? ids)))

  (dm/assert!
   "expected instance of jio/IOFactory for `input`"
   (io/coercible? output))

  (let [id (uuid/next)
        tp (ct/tpoint)
        ab (volatile! false)
        cs (volatile! nil)]
    (try
      (l/info :hint "start exportation" :export-id (str id))
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
        (l/info :hint "exportation finished" :export-id (str id)
                :elapsed (str (inst-ms (tp)) "ms")
                :aborted @ab
                :cause @cs)))))

(defn import-files!
  [{:keys [::bfc/input] :as cfg}]

  (dm/assert!
   "expected valid profile-id and project-id on `cfg`"
   (and (uuid? (::bfc/profile-id cfg))
        (uuid? (::bfc/project-id cfg))))

  (dm/assert!
   "expected instance of jio/IOFactory for `input`"
   (satisfies? jio/IOFactory input))

  (let [id (uuid/next)
        tp (ct/tpoint)
        cs (volatile! nil)]

    (l/info :hint "import: started" :id (str id))
    (try
      (binding [*position* (atom 0)]
        (pu/with-open [input (io/input-stream input)]
          (read-import! (assoc cfg ::bfc/input input))))

      (catch ZstdIOException cause
        (ex/raise :type :validation
                  :code :invalid-penpot-file
                  :hint "invalid penpot file received: probably truncated"
                  :cause cause))

      (catch Throwable cause
        (vreset! cs cause)
        (throw cause))

      (finally
        (l/info :hint "import: terminated"
                :id (str id)
                :elapsed (ct/format-duration (tp))
                :error? (some? @cs))))))

