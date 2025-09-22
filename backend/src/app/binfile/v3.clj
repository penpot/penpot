;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.binfile.v3
  "A ZIP based binary file exportation"
  (:refer-clojure :exclude [read])
  (:require
   [app.binfile.cleaner :as bfl]
   [app.binfile.common :as bfc]
   [app.binfile.migrations :as bfm]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.features :as cfeat]
   [app.common.files.migrations :as-alias fmg]
   [app.common.json :as json]
   [app.common.logging :as l]
   [app.common.media :as cmedia]
   [app.common.schema :as sm]
   [app.common.thumbnails :as cth]
   [app.common.time :as ct]
   [app.common.types.color :as ctcl]
   [app.common.types.component :as ctc]
   [app.common.types.file :as ctf]
   [app.common.types.page :as ctp]
   [app.common.types.plugins :as ctpg]
   [app.common.types.shape :as cts]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.typography :as cty]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.db.sql :as-alias sql]
   [app.storage :as sto]
   [app.storage.impl :as sto.impl]
   [app.util.events :as events]
   [clojure.java.io :as jio]
   [cuerdas.core :as str]
   [datoteka.fs :as fs]
   [datoteka.io :as io])
  (:import
   java.io.File
   java.io.InputStream
   java.io.OutputStreamWriter
   java.lang.AutoCloseable
   java.util.zip.ZipEntry
   java.util.zip.ZipFile
   java.util.zip.ZipOutputStream))

;; --- SCHEMA

(def ^:private schema:manifest
  [:map {:title "Manifest"}
   [:version ::sm/int]
   [:type :string]
   [:referer {:optional true} :string]
   [:generated-by {:optional true} :string]

   [:files
    [:vector
     [:map
      [:id ::sm/uuid]
      [:name :string]
      [:features ::cfeat/features]]]]

   [:relations {:optional true}
    [:vector
     [:tuple ::sm/uuid ::sm/uuid]]]])

(def ^:private schema:storage-object
  [:map {:title "StorageObject"}
   [:id ::sm/uuid]
   [:size ::sm/int]
   [:content-type :string]
   [:bucket [::sm/one-of {:format :string} sto/valid-buckets]]
   [:hash {:optional true} :string]])

(def ^:private schema:file-thumbnail
  [:map {:title "FileThumbnail"}
   [:file-id ::sm/uuid]
   [:page-id ::sm/uuid]
   [:frame-id ::sm/uuid]
   [:tag :string]
   [:media-id ::sm/uuid]])

(def ^:private schema:file
  [:merge
   ctf/schema:file
   [:map [:options {:optional true} ctf/schema:options]]])

;; --- HELPERS

(defn- default-now
  [o]
  (or o (ct/now)))

;; --- ENCODERS

(def encode-file
  (sm/encoder schema:file sm/json-transformer))

(def encode-page
  (sm/encoder ctp/schema:page sm/json-transformer))

(def encode-shape
  (sm/encoder cts/schema:shape sm/json-transformer))

(def encode-media
  (sm/encoder ctf/schema:media sm/json-transformer))

(def encode-component
  (sm/encoder ctc/schema:component sm/json-transformer))

(def encode-color
  (sm/encoder ctcl/schema:library-color sm/json-transformer))

(def encode-typography
  (sm/encoder cty/schema:typography sm/json-transformer))

(def encode-tokens-lib
  (sm/encoder ctob/schema:tokens-lib sm/json-transformer))

(def encode-plugin-data
  (sm/encoder ctpg/schema:plugin-data sm/json-transformer))

(def encode-storage-object
  (sm/encoder schema:storage-object sm/json-transformer))

(def encode-file-thumbnail
  (sm/encoder schema:file-thumbnail sm/json-transformer))

;; --- DECODERS

(def decode-manifest
  (sm/decoder schema:manifest sm/json-transformer))

(def decode-media
  (sm/decoder ctf/schema:media sm/json-transformer))

(def decode-component
  (sm/decoder ctc/schema:component sm/json-transformer))

(def decode-color
  (sm/decoder ctcl/schema:library-color sm/json-transformer))

(def decode-file
  (sm/decoder schema:file sm/json-transformer))

(def decode-page
  (sm/decoder ctp/schema:page sm/json-transformer))

(def decode-shape
  (sm/decoder cts/schema:shape sm/json-transformer))

(def decode-typography
  (sm/decoder cty/schema:typography sm/json-transformer))

(def decode-tokens-lib
  (sm/decoder ctob/schema:tokens-lib sm/json-transformer))

(def decode-plugin-data
  (sm/decoder ctpg/schema:plugin-data sm/json-transformer))

(def decode-storage-object
  (sm/decoder schema:storage-object sm/json-transformer))

(def decode-file-thumbnail
  (sm/decoder schema:file-thumbnail sm/json-transformer))

;; --- VALIDATORS

(def validate-manifest
  (sm/check-fn schema:manifest))

(def validate-file
  (sm/check-fn ctf/schema:file))

(def validate-page
  (sm/check-fn ctp/schema:page))

(def validate-shape
  (sm/check-fn cts/schema:shape))

(def validate-media
  (sm/check-fn ctf/schema:media))

(def validate-color
  (sm/check-fn ctcl/schema:library-color))

(def validate-component
  (sm/check-fn ctc/schema:component))

(def validate-typography
  (sm/check-fn cty/schema:typography))

(def validate-tokens-lib
  (sm/check-fn ctob/schema:tokens-lib))

(def validate-plugin-data
  (sm/check-fn ctpg/schema:plugin-data))

(def validate-storage-object
  (sm/check-fn schema:storage-object))

(def validate-file-thumbnail
  (sm/check-fn schema:file-thumbnail))

;; --- EXPORT IMPL

(defn- write-entry!
  [^ZipOutputStream output ^String path data]
  (.putNextEntry output (ZipEntry. path))
  (let [writer (OutputStreamWriter. output "UTF-8")]
    (json/write writer data :indent true :key-fn json/write-camel-key)
    (.flush writer))
  (.closeEntry output))

(defn- get-file
  [{:keys [::bfc/embed-assets ::bfc/include-libraries] :as cfg} file-id]

  (when (and include-libraries embed-assets)
    (throw (IllegalArgumentException.
            "the `include-libraries` and `embed-assets` are mutally excluding options")))

  (let [detach?  (and (not embed-assets) (not include-libraries))]
    (db/tx-run! cfg (fn [cfg]
                      (cond-> (bfc/get-file cfg file-id {::sql/for-update true})
                        detach?
                        (-> (ctf/detach-external-references file-id)
                            (dissoc :libraries))

                        embed-assets
                        (update :data #(bfc/embed-assets cfg % file-id))

                        :always
                        (bfc/clean-file-features))))))

(defn- export-storage-objects
  [{:keys [::output] :as cfg}]
  (let [storage (sto/resolve cfg)]
    (doseq [id (-> bfc/*state* deref :storage-objects not-empty)]
      (let [sobject (sto/get-object storage id)
            smeta   (meta sobject)
            ext     (cmedia/mtype->extension (:content-type smeta))
            path    (str "objects/" id ".json")
            params  (-> (meta sobject)
                        (assoc :id (:id sobject))
                        (assoc :size (:size sobject))
                        (encode-storage-object))]

        (write-entry! output path params)

        (with-open [input (sto/get-object-data storage sobject)]
          (.putNextEntry ^ZipOutputStream output (ZipEntry. (str "objects/" id ext)))
          (io/copy input output :size (:size sobject))
          (.closeEntry ^ZipOutputStream output))))))

(defn- export-file
  [{:keys [::file-id ::output] :as cfg}]
  (let [file         (get-file cfg file-id)

        media        (->> (bfc/get-file-media cfg file)
                          (map (fn [media]
                                 (dissoc media :file-id))))

        data         (:data file)
        typographies (:typographies data)
        components   (:components data)
        colors       (:colors data)
        tokens-lib   (:tokens-lib data)

        pages        (:pages data)
        pages-index  (:pages-index data)

        thumbnails   (bfc/get-file-object-thumbnails cfg file-id)]

    (vswap! bfc/*state* update :files assoc file-id
            {:id file-id
             :name (:name file)
             :features (:features file)})

    (let [file (cond-> (select-keys file bfc/file-attrs)
                 (:options data)
                 (assoc :options (:options data))

                 :always
                 (dissoc :data))

          file (cond-> file
                 :always
                 (encode-file))

          path (str "files/" file-id ".json")]
      (write-entry! output path file))

    (doseq [[index page-id] (d/enumerate pages)]
      (let [path    (str "files/" file-id "/pages/" page-id ".json")
            page    (get pages-index page-id)
            objects (:objects page)
            page    (-> page
                        (dissoc :objects)
                        (assoc :index index))
            page    (encode-page page)]

        (write-entry! output path page)

        (doseq [[shape-id shape] objects]
          (let [path  (str "files/" file-id "/pages/" page-id "/" shape-id ".json")
                shape (assoc shape :page-id page-id)
                shape (encode-shape shape)]
            (write-entry! output path shape)))))

    (vswap! bfc/*state* bfc/collect-storage-objects media)
    (vswap! bfc/*state* bfc/collect-storage-objects thumbnails)

    (doseq [{:keys [id] :as media} media]
      (let [path  (str "files/" file-id "/media/" id ".json")
            media (encode-media media)]
        (write-entry! output path media)))

    (doseq [thumbnail thumbnails]
      (let [data (cth/parse-object-id (:object-id thumbnail))
            path (str "files/" file-id "/thumbnails/" (:tag data) "/" (:page-id data)
                      "/" (:frame-id data) ".json")
            data (-> data
                     (assoc :media-id (:media-id thumbnail))
                     (encode-file-thumbnail))]
        (write-entry! output path data)))

    (doseq [[id component] components]
      (let [path      (str "files/" file-id "/components/" id ".json")
            component (encode-component component)]
        (write-entry! output path component)))

    (doseq [[id color] colors]
      (let [path  (str "files/" file-id "/colors/" id ".json")
            color (-> (encode-color color)
                      (dissoc :file-id))
            color (cond-> color
                    (and (contains? color :path)
                         (str/empty? (:path color)))
                    (dissoc :path))]
        (write-entry! output path color)))

    (doseq [[id object] typographies]
      (let [path       (str "files/" file-id "/typographies/" id ".json")
            typography (encode-typography object)]
        (write-entry! output path typography)))

    (when (and tokens-lib
               (not (ctob/empty-lib? tokens-lib)))
      (let [path           (str "files/" file-id "/tokens.json")
            encoded-tokens (encode-tokens-lib tokens-lib)]
        (write-entry! output path encoded-tokens)))))

(defn- export-files
  [{:keys [::bfc/ids ::bfc/include-libraries ::output] :as cfg}]
  (let [ids  (into ids (when include-libraries (bfc/get-libraries cfg ids)))
        rels (if include-libraries
               (->> (bfc/get-files-rels cfg ids)
                    (mapv (juxt :file-id :library-file-id)))
               [])]

    (vswap! bfc/*state* assoc :files (d/ordered-map))

    ;; Write all the exporting files
    (doseq [[index file-id] (d/enumerate ids)]
      (-> cfg
          (assoc ::file-id file-id)
          (assoc ::file-seqn index)
          (export-file)))

    ;; Write manifest file
    (let [files  (:files @bfc/*state*)
          params {:type "penpot/export-files"
                  :version 1
                  :generated-by (str "penpot/" (:full cf/version))
                  :refer "penpot"
                  :files (vec (vals files))
                  :relations rels}]
      (write-entry! output "manifest.json" params))))

;; --- IMPORT IMPL

(defn- read-zip-entries
  [^ZipFile input]
  (into #{} (iterator-seq (.entries input))))

(defn- get-zip-entry*
  [^ZipFile input ^String path]
  (.getEntry input path))

(defn- get-zip-entry
  [input path]
  (let [entry (get-zip-entry* input path)]
    (when-not entry
      (ex/raise :type :validation
                :code :inconsistent-penpot-file
                :hint "the penpot file seems corrupt, missing underlying zip entry"
                :path path))
    entry))

(defn- get-zip-entry-size
  [^ZipEntry entry]
  (.getSize entry))

(defn- zip-entry-name
  [^ZipEntry entry]
  (.getName entry))

(defn- zip-entry-stream
  ^InputStream
  [^ZipFile input ^ZipEntry entry]
  (.getInputStream input entry))

(defn- zip-entry-reader
  [^ZipFile input ^ZipEntry entry]
  (-> (zip-entry-stream input entry)
      (io/reader :encoding "UTF-8")))

(defn- zip-entry-storage-content
  "Wraps a ZipFile and ZipEntry into a penpot storage compatible
  object and avoid creating temporal objects"
  [input entry]
  (let [hash  (delay (->> entry
                          (zip-entry-stream input)
                          (sto.impl/calculate-hash)))]
    (reify
      sto.impl/IContentObject
      (get-size [_]
        (get-zip-entry-size entry))

      sto.impl/IContentHash
      (get-hash [_]
        (deref hash))

      jio/IOFactory
      (make-reader [this opts]
        (jio/make-reader this opts))
      (make-writer [_ _]
        (throw (UnsupportedOperationException. "not implemented")))

      (make-input-stream [_ _]
        (zip-entry-stream input entry))
      (make-output-stream [_ _]
        (throw (UnsupportedOperationException. "not implemented"))))))

(defn- read-manifest
  [^ZipFile input]
  (let [entry (get-zip-entry input "manifest.json")]
    (with-open [^AutoCloseable reader (zip-entry-reader input entry)]
      (let [manifest (json/read reader :key-fn json/read-kebab-key)]
        (decode-manifest manifest)))))

(defn- match-media-entry-fn
  [file-id]
  (let [pattern (str "^files/" file-id "/media/([^/]+).json$")
        pattern (re-pattern pattern)]
    (fn [entry]
      (when-let [[_ id] (re-matches pattern (zip-entry-name entry))]
        {:entry entry
         :id (parse-uuid id)}))))

(defn- match-color-entry-fn
  [file-id]
  (let [pattern (str "^files/" file-id "/colors/([^/]+).json$")
        pattern (re-pattern pattern)]
    (fn [entry]
      (when-let [[_ id] (re-matches pattern (zip-entry-name entry))]
        {:entry entry
         :id (parse-uuid id)}))))

(defn- match-component-entry-fn
  [file-id]
  (let [pattern (str "^files/" file-id "/components/([^/]+).json$")
        pattern (re-pattern pattern)]
    (fn [entry]
      (when-let [[_ id] (re-matches pattern (zip-entry-name entry))]
        {:entry entry
         :id (parse-uuid id)}))))

(defn- match-typography-entry-fn
  [file-id]
  (let [pattern (str "^files/" file-id "/typographies/([^/]+).json$")
        pattern (re-pattern pattern)]
    (fn [entry]
      (when-let [[_ id] (re-matches pattern (zip-entry-name entry))]
        {:entry entry
         :id (parse-uuid id)}))))

(defn- match-tokens-lib-entry-fn
  [file-id]
  (let [pattern (str "^files/" file-id "/tokens.json$")
        pattern (re-pattern pattern)]
    (fn [entry]
      (when-let [[_] (re-matches pattern (zip-entry-name entry))]
        {:entry entry}))))

(defn- match-thumbnail-entry-fn
  [file-id]
  (let [pattern (str "^files/" file-id "/thumbnails/([^/]+)/([^/]+)/([^/]+).json$")
        pattern (re-pattern pattern)]
    (fn [entry]
      (when-let [[_ tag page-id frame-id] (re-matches pattern (zip-entry-name entry))]
        {:entry entry
         :tag tag
         :page-id (parse-uuid page-id)
         :frame-id (parse-uuid frame-id)
         :file-id file-id}))))

(defn- match-page-entry-fn
  [file-id]
  (let [pattern (str "^files/" file-id "/pages/([^/]+).json$")
        pattern (re-pattern pattern)]
    (fn [entry]
      (when-let [[_ id] (re-matches pattern (zip-entry-name entry))]
        {:entry entry
         :id (parse-uuid id)}))))

(defn- match-shape-entry-fn
  [file-id page-id]
  (let [pattern (str "^files/" file-id "/pages/" page-id "/([^/]+).json$")
        pattern (re-pattern pattern)]
    (fn [entry]
      (when-let [[_ id] (re-matches pattern (zip-entry-name entry))]
        {:entry entry
         :page-id page-id
         :id (parse-uuid id)}))))

(defn- match-storage-entry-fn
  []
  (let [pattern "^objects/([^/]+).json$"
        pattern (re-pattern pattern)]
    (fn [entry]
      (when-let [[_ id] (re-matches pattern (zip-entry-name entry))]
        {:entry entry
         :id (parse-uuid id)}))))

(defn- read-entry
  [^ZipFile input entry]
  (with-open [^AutoCloseable reader (zip-entry-reader input entry)]
    (json/read reader :key-fn json/read-kebab-key)))

(defn- read-plain-entry
  [^ZipFile input entry]
  (with-open [^AutoCloseable reader (zip-entry-reader input entry)]
    (json/read reader)))

(defn- read-file
  [{:keys [::bfc/input ::bfc/timestamp]} file-id]
  (let [path  (str "files/" file-id ".json")
        entry (get-zip-entry input path)]
    (-> (read-entry input entry)
        (decode-file)
        (update :revn d/nilv 1)
        (update :created-at d/nilv timestamp)
        (update :modified-at d/nilv timestamp)
        (validate-file))))

(defn- read-file-plugin-data
  [{:keys [::bfc/input]} file-id]
  (let [path  (str "files/" file-id "/plugin-data.json")
        entry (get-zip-entry* input path)]
    (some->> entry
             (read-entry input)
             (decode-plugin-data)
             (validate-plugin-data))))

(defn- read-file-media
  [{:keys [::bfc/input ::entries]} file-id]
  (->> (keep (match-media-entry-fn file-id) entries)
       (reduce (fn [result {:keys [id entry]}]
                 (let [object (->> (read-entry input entry)
                                   (decode-media)
                                   (validate-media))
                       object (-> object
                                  (assoc :file-id file-id)
                                  (update :created-at default-now)
                                  ;; FIXME: this is set default to true for
                                  ;; setting a value, this prop is no longer
                                  ;; relevant;
                                  (assoc :is-local true))]
                   (if (= id (:id object))
                     (conj result object)
                     result)))
               [])
       (not-empty)))

(defn- read-file-colors
  [{:keys [::bfc/input ::entries]} file-id]
  (->> (keep (match-color-entry-fn file-id) entries)
       (reduce (fn [result {:keys [id entry]}]
                 (let [object (->> (read-entry input entry)
                                   (decode-color)
                                   (validate-color))]
                   (if (= id (:id object))
                     (assoc result id object)
                     result)))
               {})
       (not-empty)))

(defn- read-file-components
  [{:keys [::bfc/input ::entries]} file-id]
  (let [clean-component-post-decode
        (fn [component]
          (d/update-when component :objects
                         (fn [objects]
                           (reduce-kv (fn [objects id shape]
                                        (assoc objects id (bfl/clean-shape-post-decode shape)))
                                      objects
                                      objects))))
        clean-component-pre-decode
        (fn [component]
          (d/update-when component :objects
                         (fn [objects]
                           (reduce-kv (fn [objects id shape]
                                        (assoc objects id (bfl/clean-shape-pre-decode shape)))
                                      objects
                                      objects))))]

    (->> (keep (match-component-entry-fn file-id) entries)
         (reduce (fn [result {:keys [id entry]}]
                   (let [object (->> (read-entry input entry)
                                     (clean-component-pre-decode)
                                     (decode-component)
                                     (clean-component-post-decode))]
                     (if (= id (:id object))
                       (assoc result id object)
                       result)))
                 {})
         (not-empty))))

(defn- read-file-typographies
  [{:keys [::bfc/input ::entries]} file-id]
  (->> (keep (match-typography-entry-fn file-id) entries)
       (reduce (fn [result {:keys [id entry]}]
                 (let [object (->> (read-entry input entry)
                                   (decode-typography)
                                   (validate-typography))]
                   (if (= id (:id object))
                     (assoc result id object)
                     result)))
               {})
       (not-empty)))

(defn- read-file-tokens-lib
  [{:keys [::bfc/input ::entries]} file-id]
  (when-let [entry (d/seek (match-tokens-lib-entry-fn file-id) entries)]
    (->> (read-plain-entry input entry)
         (decode-tokens-lib)
         (validate-tokens-lib))))

(defn- read-file-shapes
  [{:keys [::bfc/input ::entries] :as cfg} file-id page-id]
  (->> (keep (match-shape-entry-fn file-id page-id) entries)
       (reduce (fn [result {:keys [id entry]}]
                 (let [object (->> (read-entry input entry)
                                   (bfl/clean-shape-pre-decode)
                                   (decode-shape)
                                   (bfl/clean-shape-post-decode))]
                   (if (= id (:id object))
                     (assoc result id object)
                     result)))
               {})
       (not-empty)))

(defn- read-file-pages
  [{:keys [::bfc/input ::entries] :as cfg} file-id]
  (->> (keep (match-page-entry-fn file-id) entries)
       (keep (fn [{:keys [id entry]}]
               (let [page (->> (read-entry input entry)
                               (decode-page))
                     page (dissoc page :options)]
                 (when (= id (:id page))
                   (let [objects (read-file-shapes cfg file-id id)]
                     (assoc page :objects objects))))))
       (sort-by :index)
       (reduce (fn [result {:keys [id] :as page}]
                 (assoc result id (dissoc page :index)))
               (d/ordered-map))))

(defn- read-file-thumbnails
  [{:keys [::bfc/input ::entries] :as cfg} file-id]
  (->> (keep (match-thumbnail-entry-fn file-id) entries)
       (reduce (fn [result {:keys [page-id frame-id tag entry]}]
                 (let [object (->> (read-entry input entry)
                                   (decode-file-thumbnail)
                                   (validate-file-thumbnail))]
                   (if (and (= frame-id (:frame-id object))
                            (= page-id (:page-id object))
                            (= tag (:tag object)))
                     (conj result object)
                     result)))
               [])
       (not-empty)))

(defn- read-file-data
  [cfg file-id]
  (let [colors       (read-file-colors cfg file-id)
        typographies (read-file-typographies cfg file-id)
        tokens-lib   (read-file-tokens-lib cfg file-id)
        components   (read-file-components cfg file-id)
        plugin-data  (read-file-plugin-data cfg file-id)
        pages        (read-file-pages cfg file-id)]
    {:pages (-> pages keys vec)
     :pages-index (into {} pages)
     :colors colors
     :typographies typographies
     :tokens-lib tokens-lib
     :components components
     :plugin-data plugin-data}))

(defn- import-file
  [{:keys [::bfc/project-id] :as cfg} {file-id :id file-name :name}]
  (let [file-id'   (bfc/lookup-index file-id)
        file       (read-file cfg file-id)
        media      (read-file-media cfg file-id)
        thumbnails (read-file-thumbnails cfg file-id)]

    (l/dbg :hint "processing file"
           :id (str file-id')
           :prev-id (str file-id)
           :features (str/join "," (:features file))
           :version (:version file)
           ::l/sync? true)

    (events/tap :progress {:section :file :name file-name})

    (when media
      ;; Update index with media
      (l/dbg :hint "update media index"
             :file-id (str file-id')
             :total (count media)
             ::l/sync? true)

      (vswap! bfc/*state* update :index bfc/update-index (map :id media))
      (vswap! bfc/*state* update :media into media))

    (when thumbnails
      (l/dbg :hint "update thumbnails index"
             :file-id (str file-id')
             :total (count thumbnails)
             ::l/sync? true)

      (vswap! bfc/*state* update :index bfc/update-index (map :media-id thumbnails))
      (vswap! bfc/*state* update :thumbnails into thumbnails))

    (let [data (-> (read-file-data cfg file-id)
                   (d/without-nils)
                   (assoc :id file-id')
                   (cond-> (:options file)
                     (assoc :options (:options file))))

          file (-> (select-keys file bfc/file-attrs)
                   (assoc :id file-id')
                   (assoc :data data)
                   (assoc :name file-name)
                   (assoc :project-id project-id)
                   (dissoc :options))

          file  (bfc/process-file cfg file)
          file  (ctf/check-file file)]

      (bfm/register-pending-migrations! cfg file)
      (bfc/save-file! cfg file)

      file-id')))

(defn- import-file-relations
  [{:keys [::db/conn ::manifest ::bfc/timestamp] :as cfg}]
  (events/tap :progress {:section :relations})
  (doseq [[file-id libr-id] (:relations manifest)]

    (let [file-id (bfc/lookup-index file-id)
          libr-id (bfc/lookup-index libr-id)]

      (when (and file-id libr-id)
        (l/dbg :hint "create file library link"
               :file-id (str file-id)
               :lib-id (str libr-id)
               ::l/sync? true)
        (db/insert! conn :file-library-rel
                    {:synced-at timestamp
                     :file-id file-id
                     :library-file-id libr-id})))))

(defn- import-storage-objects
  [{:keys [::bfc/input ::entries ::bfc/timestamp] :as cfg}]
  (events/tap :progress {:section :storage-objects})

  (let [storage (sto/resolve cfg)
        entries (keep (match-storage-entry-fn) entries)]

    (doseq [{:keys [id entry]} entries]
      (let [object (->> (read-entry input entry)
                        (decode-storage-object)
                        (validate-storage-object))]

        (when (not= id (:id object))
          (ex/raise :type :validation
                    :code :inconsistent-penpot-file
                    :hint "the penpot file seems corrupt, found unexpected uuid (storage-object-id)"
                    :expected-id (str id)
                    :found-id (str (:id object))))

        (let [ext     (cmedia/mtype->extension (:content-type object))
              path    (str "objects/" id ext)
              content (->> path
                           (get-zip-entry input)
                           (zip-entry-storage-content input))]

          (when (not= (:size object) (sto/get-size content))
            (ex/raise :type :validation
                      :code :inconsistent-penpot-file
                      :hint "found corrupted storage object: size does not match"
                      :path path
                      :expected-size (:size object)
                      :found-size (sto/get-size content)))

          (when-let [hash (get object :hash)]
            (when (not= hash (sto/get-hash content))
              (ex/raise :type :validation
                        :code :inconsistent-penpot-file
                        :hint "found corrupted storage object: hash does not match"
                        :path path
                        :expected-hash (:hash object)
                        :found-hash (sto/get-hash content))))

          (let [params  (-> object
                            (dissoc :id :size)
                            (assoc ::sto/content content)
                            (assoc ::sto/deduplicate? true)
                            (assoc ::sto/touched-at timestamp))
                sobject (sto/put-object! storage params)]

            (l/dbg :hint "persisted storage object"
                   :id (str (:id sobject))
                   :prev-id (str id)
                   :bucket (:bucket params)
                   ::l/sync? true)

            (vswap! bfc/*state* update :index assoc id (:id sobject))))))))

(defn- import-file-media
  [{:keys [::db/conn] :as cfg}]
  (events/tap :progress {:section :media})

  (doseq [item (:media @bfc/*state*)]
    (let [params (-> item
                     (update :id bfc/lookup-index)
                     (update :file-id bfc/lookup-index)
                     (d/update-when :media-id bfc/lookup-index)
                     (d/update-when :thumbnail-id bfc/lookup-index))]

      (l/dbg :hint "inserting file media object"
             :old-id (str (:id item))
             :id (str (:id params))
             :file-id (str (:file-id params))
             ::l/sync? true)

      (db/insert! conn :file-media-object params
                  ::db/on-conflict-do-nothing? (::bfc/overwrite cfg)))))

(defn- import-file-thumbnails
  [{:keys [::db/conn] :as cfg}]
  (events/tap :progress {:section :thumbnails})
  (doseq [item (:thumbnails @bfc/*state*)]
    (let [file-id   (bfc/lookup-index (:file-id item))
          media-id  (bfc/lookup-index (:media-id item))
          object-id (-> (assoc item :file-id file-id)
                        (cth/fmt-object-id))
          params    {:file-id file-id
                     :object-id object-id
                     :tag (:tag item)
                     :media-id media-id}]

      (l/dbg :hint "inserting file object thumbnail"
             :file-id (str file-id)
             :media-id (str media-id)
             ::l/sync? true)

      (db/insert! conn :file-tagged-object-thumbnail params
                  {::db/on-conflict-do-nothing? true}))))

(defn- import-files*
  [{:keys [::manifest] :as cfg}]
  (bfc/disable-database-timeouts! cfg)

  (vswap! bfc/*state* update :index bfc/update-index (:files manifest) :id)

  (let [files  (get manifest :files)
        result (reduce (fn [result {:keys [id] :as file}]
                         (let [name' (get file :name)
                               name' (if (map? name)
                                       (get name id)
                                       name')
                               file (assoc file :name name')]
                           (conj result (import-file cfg file))))
                       []
                       files)]

    (import-file-relations cfg)
    (import-storage-objects cfg)
    (import-file-media cfg)
    (import-file-thumbnails cfg)

    (bfm/apply-pending-migrations! cfg)

    result))

(defn- import-file-and-overwrite*
  [{:keys [::manifest ::bfc/file-id] :as cfg}]

  (when (not= 1 (count (:files manifest)))
    (ex/raise :type :validation
              :code :invalid-condition
              :hint "unable to perform in-place update with binfile containing more than 1 file"
              :manifest manifest))

  (bfc/disable-database-timeouts! cfg)

  (let [ref-file (bfc/get-minimal-file cfg file-id ::db/for-update true)
        file     (first (get manifest :files))
        cfg      (assoc cfg ::bfc/overwrite true)]

    (vswap! bfc/*state* update :index assoc (:id file) file-id)

    (binding [bfc/*options* cfg
              bfc/*reference-file* ref-file]

      (import-file cfg file)
      (import-storage-objects cfg)
      (import-file-media cfg)

      (bfc/invalidate-thumbnails cfg file-id)
      (bfm/apply-pending-migrations! cfg)

      [file-id])))

(defn- import-files
  [{:keys [::bfc/timestamp ::bfc/input] :or {timestamp (ct/now)} :as cfg}]

  (assert (instance? ZipFile input) "expected zip file")
  (assert (ct/inst? timestamp) "expected valid instant")

  (let [manifest (-> (read-manifest input)
                     (validate-manifest))
        entries  (read-zip-entries input)
        cfg      (-> cfg
                     (assoc ::entries entries)
                     (assoc ::manifest manifest)
                     (assoc ::bfc/timestamp timestamp))]

    (when-not (= "penpot/export-files" (:type manifest))
      (ex/raise :type :validation
                :code :invalid-binfile-v3-manifest
                :hint "unexpected type on manifest"
                :manifest manifest))

    ;; Check if all files referenced on manifest are present
    (doseq [{file-id :id features :features} (:files manifest)]
      (let [path (str "files/" file-id ".json")]

        (when-not (get-zip-entry input path)
          (ex/raise :type :validation
                    :code :invalid-binfile-v3
                    :hint "some files referenced on manifest not found"
                    :path path
                    :file-id file-id))

        (cfeat/check-supported-features! features)))

    (events/tap :progress {:section :manifest})

    (binding [bfc/*state* (volatile! {:media [] :index {}})]
      (if (::bfc/file-id cfg)
        (db/tx-run! cfg import-file-and-overwrite*)
        (db/tx-run! cfg import-files*)))))

;; --- PUBLIC API

(defn export-files!
  "Do the exportation of a specified file in custom penpot binary
  format. There are some options available for customize the output:

  `::bfc/include-libraries`: additionally to the specified file, all the
  linked libraries also will be included (including transitive
  dependencies).

  `::bfc/embed-assets`: instead of including the libraries, embed in the
  same file library all assets used from external libraries."

  [{:keys [::bfc/ids] :as cfg} output]

  (assert
   (and (set? ids) (every? uuid? ids))
   "expected a set of uuid's for `::bfc/ids` parameter")

  (assert
   (satisfies? jio/IOFactory output)
   "expected instance of jio/IOFactory for `input`")

  (let [id (uuid/next)
        tp (ct/tpoint)
        ab (volatile! false)
        cs (volatile! nil)]
    (try
      (l/info :hint "start exportation" :export-id (str id))
      (binding [bfc/*state* (volatile! (bfc/initial-state))]
        (with-open [^AutoCloseable output (io/output-stream output)]
          (with-open [^AutoCloseable output (ZipOutputStream. output)]
            (let [cfg (assoc cfg ::output output)]
              (export-files cfg)
              (export-storage-objects cfg)))))

      (catch java.util.zip.ZipException cause
        (vreset! cs cause)
        (vreset! ab true)
        (throw cause))

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

  (assert
   (and (uuid? (::bfc/profile-id cfg))
        (uuid? (::bfc/project-id cfg)))
   "expected valid profile-id and project-id on `cfg`")

  (assert
   (io/coercible? input)
   "expected instance of jio/IOFactory for `input`")

  (let [id (uuid/next)
        tp (ct/tpoint)
        cs (volatile! nil)]

    (l/info :hint "import: started" :id (str id))
    (try
      (with-open [input (ZipFile. ^File (fs/file input))]
        (import-files (assoc cfg ::bfc/input input)))

      (catch Throwable cause
        (vreset! cs cause)
        (throw cause))

      (finally
        (l/info :hint "import: terminated"
                :id (str id)
                :elapsed (ct/format-duration (tp))
                :error? (some? @cs))))))

(defn get-manifest
  [path]
  (with-open [^AutoCloseable input (ZipFile. ^File (fs/file path))]
    (-> (read-manifest input)
        (validate-manifest))))
