;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns lib.export
  "A .penpot export implementation"
  (:require
   [app.common.data :as d]
   [app.common.files.builder :as fb]
   [app.common.files.page-chunks :as pc]
   [app.common.files.shape-compact :as fsc]
   [app.common.json :as json]
   [app.common.media :as media]
   [app.common.schema :as sm]
   [app.common.types.color :as types.color]
   [app.common.types.component :as types.component]
   [app.common.types.file :as types.file]
   [app.common.types.page :as types.page]
   [app.common.types.plugins :as ctpg]
   [app.common.types.shape :as types.shape]
   [app.common.types.tokens-lib :as types.tokens-lib]
   [app.common.types.typography :as types.typography]
   [app.util.zip :as zip]
   [cuerdas.core :as str]
   [promesa.core :as p]))

(def ^:private schema:file
  [:merge
   types.file/schema:file
   [:map [:options {:optional true} types.file/schema:options]]])

(def ^:private encode-file
  (sm/encoder schema:file sm/json-transformer))

(def ^:private encode-page
  (sm/encoder types.page/schema:page sm/json-transformer))

(def ^:private encode-shape
  (sm/encoder types.shape/schema:shape sm/json-transformer))

(def ^:private encode-component
  (sm/encoder types.component/schema:component sm/json-transformer))

(def encode-file-media
  (sm/encoder types.file/schema:media sm/json-transformer))

(def encode-color
  (sm/encoder types.color/schema:color sm/json-transformer))

(def encode-typography
  (sm/encoder types.typography/schema:typography sm/json-transformer))

(def encode-tokens-lib
  (sm/encoder types.tokens-lib/schema:tokens-lib sm/json-transformer))

(def encode-plugin-data
  (sm/encoder ctpg/schema:plugin-data sm/json-transformer))

(def ^:private valid-buckets
  #{"file-media-object"
    "team-font-variant"
    "file-object-thumbnail"
    "file-thumbnail"
    "profile"
    "file-data"
    "file-data-fragment"
    "file-change"})

(def ^:private schema:storage-object
  [:map {:title "StorageObject"}
   [:id ::sm/uuid]
   [:size ::sm/int]
   [:content-type :string]
   [:bucket [::sm/one-of {:format :string} valid-buckets]]
   [:hash :string]])

(def encode-storage-object
  (sm/encoder schema:storage-object sm/json-transformer))

(def ^:private file-attrs
  #{:id
    :name
    :migrations
    :features
    :is-shared
    :version})

(defn- encode-shape*
  [{:keys [type] :as shape}]
  (let [shape (if (or (= type :path)
                      (= type :bool))
                (update shape :content vec)
                shape)]
    (-> shape encode-shape json/encode)))

;; --- Export units
;;
;; A unit is one planned step of the export: the path the progress reports and
;; a function that answers its zip entries lazily, when the unit is written.
;; Building a unit never serializes anything, so the export does not hold the
;; whole document serialized in memory: only the chunk being written is alive.

(defn- unit
  [path entries]
  {:path path :entries entries})

(defn- entry-unit
  "A unit with a single zip entry whose payload is computed when it is written."
  [path payload-fn]
  (unit path (fn [] (list [path (payload-fn)]))))

(defn- join-lines
  "The chunk payload: one line per entry and a final newline, built once."
  [lines]
  (str/join "\n" (conj lines "")))

(defn- family-chunk-path
  "The chunk path of a packed family. The library writes plain json only, so
  the family entry is always `<family>.json`."
  [path index]
  (str (subs path 0 (- (count path) 5)) "/chunks/" index ".json"))

(defn- family-unit
  "A packed family as one unit: the `{:chunks n}` header plus its chunks as
  jsonl lines. The count is only known once the chunks are written, so the
  header is the last entry of the unit. A line is serialized once by
  `chunk-lines` and joined once here."
  [path objects encode-fn chunk-size]
  (unit path
        (fn []
          (let [n      (volatile! 0)
                chunks (pc/chunk-lines
                        objects
                        chunk-size
                        (fn [k value]
                          (json/encode [(str k) (encode-fn value)])))]
            (lazy-seq
             (concat
              (map (fn [lines]
                     (let [index @n]
                       (vswap! n inc)
                       [(family-chunk-path path index) (join-lines lines)]))
                   chunks)
              (lazy-seq (list [path (json/encode {:chunks @n})]))))))))

(defn- encode-color*
  [color]
  (let [color (-> color encode-color (dissoc :file-id))]
    (cond-> color
      (and (contains? color :path)
           (str/empty? (:path color)))
      (dissoc :path))))

(defn- encode-file-media*
  [file-media]
  (-> file-media (dissoc :file-id) encode-file-media))

(defn- page-unit
  "A compact page as one unit: its chunks and the page entry that names them,
  written last because it carries the count."
  [file-id page-id index page chunk-size]
  (unit (str "files/" file-id "/pages/" page-id ".json")
        (fn []
          (let [n      (volatile! 0)
                chunks (pc/chunk-lines
                        (get page :objects)
                        chunk-size
                        (fn [shape-id shape]
                          (let [shape (if (or (= (:type shape) :path)
                                              (= (:type shape) :bool))
                                        (update shape :content vec)
                                        shape)]
                            (json/encode [(str shape-id)
                                          (encode-shape (fsc/compact-object shape-id shape))]))))]
            (lazy-seq
             (concat
              (map (fn [lines]
                     (let [i @n]
                       (vswap! n inc)
                       [(pc/chunk-path file-id page-id i) (join-lines lines)]))
                   chunks)
              (lazy-seq
               (list [(str "files/" file-id "/pages/" page-id ".json")
                      (-> page
                          (dissoc :objects)
                          (assoc :index index :chunks @n)
                          json/encode)]))))))))

(defn- page-units
  [version file-id index page-id page chunk-size]
  (if (= version 2)
    [(page-unit file-id page-id index page chunk-size)]
    (cons
     (entry-unit (str "files/" file-id "/pages/" page-id ".json")
                 #(-> page
                      (dissoc :objects)
                      (assoc :index index)
                      encode-page
                      json/encode))
     (map (fn [[shape-id shape]]
            (entry-unit (str "files/" file-id "/pages/" page-id "/" shape-id ".json")
                        #(encode-shape* shape)))
          (:objects page)))))

(defn- file-media
  [state file-id]
  (->> (get state ::fb/file-media)
       (filter (fn [[_ file-media]] (= file-id (:file-id file-media))))
       (into {})))

(defn- family-units
  "The packed families of one file, in version 2. A family with no objects
  writes no entry, so it plans no unit."
  [file-id families chunk-size]
  (->> families
       (keep (fn [[name objects encode-fn]]
               (when (seq objects)
                 (family-unit (str "files/" file-id "/" name ".json")
                              objects encode-fn chunk-size))))
       vec))

(defn- generate-file-units
  [state version {:keys [id data] :as file} chunk-size]
  (let [file        (cond-> (select-keys file file-attrs)
                      (:options data)
                      (assoc :options (:options data)))
        pages       (get data :pages)
        pages-index (get data :pages-index)
        compact?    (= version 2)]
    (concat
     [(entry-unit (str "files/" id ".json")
                  #(-> file encode-file json/encode))]
     (mapcat (fn [[index page-id]]
               (page-units version id index page-id
                           (get pages-index page-id) chunk-size))
             (d/enumerate pages))
     (if compact?
       (family-units id
                     [["components" (get data :components) encode-component]
                      ["colors" (get data :colors) encode-color*]
                      ["typographies" (get data :typographies) encode-typography]
                      ["media" (file-media state id) encode-file-media*]]
                     chunk-size)
       (concat
        (map (fn [[component-id component]]
               (entry-unit (str "files/" id "/components/" component-id ".json")
                           #(-> component encode-component json/encode)))
             (get data :components))
        (map (fn [[color-id color]]
               (entry-unit (str "files/" id "/colors/" color-id ".json")
                           #(-> color encode-color* json/encode)))
             (get data :colors))
        (map (fn [[typography-id typography]]
               (entry-unit (str "files/" id "/typographies/" typography-id ".json")
                           #(-> typography encode-typography json/encode)))
             (get data :typographies))
        (map (fn [[file-media-id file-media]]
               (entry-unit (str "files/" id "/media/" file-media-id ".json")
                           #(-> file-media encode-file-media* json/encode)))
             (file-media state id))))
     (when-let [tokens-lib (get data :tokens-lib)]
       [(entry-unit (str "files/" id "/tokens.json")
                    #(-> tokens-lib encode-tokens-lib json/encode))]))))

(defn- media-binary-unit
  [state media-id media]
  (let [blob (get-in state [::fb/blobs media-id])]
    (entry-unit (str "objects/" media-id (media/mtype->extension (:content-type media)))
                (constantly (get blob :blob)))))

(defn- generate-media-units
  [state version chunk-size]
  (let [storage-objects (get state ::fb/media)]
    (if (= version 2)
      (concat
       (when (seq storage-objects)
         [(family-unit "objects.json" storage-objects identity chunk-size)])
       (map (fn [[media-id media]]
              (media-binary-unit state media-id media))
            storage-objects))
      ;; Version 1 keeps one entry per binary and one per storage-object
      ;; metadata; the file-media descriptors travel with their file.
      (mapcat (fn [[_ file-media]]
                (let [media-id (:media-id file-media)
                      media    (get-in state [::fb/media media-id])
                      blob     (get-in state [::fb/blobs media-id])]
                  (list
                   (entry-unit (str "objects/" media-id (media/mtype->extension (:content-type media)))
                               (constantly (get blob :blob)))
                   (entry-unit (str "objects/" media-id ".json")
                               #(json/encode media)))))
              (get state ::fb/file-media)))))

(defn- manifest-params
  [state version]
  (let [opts  (get state :options)
        files (->> (get state ::fb/files)
                   (mapv (fn [[file-id file]]
                           {:id file-id
                            :name (:name file)
                            :features (:features file)})))
        params {:type "penpot/export-files"
                :version version
                :generated-by "penpot-library/%version%"
                :referer (get opts :referer)
                :files files
                :relations (->> (:relations state)
                                (mapv vec))}]
    (d/without-nils params)))

(defn- generate-units
  [state version chunk-size]
  (let [state (deref state)]
    (concat
     [(entry-unit "manifest.json" #(json/encode (manifest-params state version)))]
     (mapcat #(generate-file-units state version % chunk-size)
             (vals (get state ::fb/files)))
     (generate-media-units state version chunk-size))))

;; --- Export driver
;;
;; The units are generated twice: once to count them for the progress report
;; and once to write them. Building a unit is cheap (a path and a closure), so
;; the first pass does not serialize anything and the second one can consume the
;; units one at a time, letting the written chunks be collected.
;;
;; The three exporters take the same options: `:version` (1 by default, or 2),
;; `:chunk-size` (the budget of one chunk of a version 2 package, in bytes of
;; uncompressed json, `default-chunk-size` by default; a positive integer up to
;; that default, because the option only makes chunks smaller and a bigger one
;; would pass the importer's per-entry cap) and `:on-progress`.

(def ^:private ^:function noop-fn
  (constantly nil))

(defn- write-entries
  [writer entries]
  (letfn [(step [writer entries]
            (if-let [[path payload] (first entries)]
              (p/then (zip/add writer path payload)
                      (fn [_] (step writer (next entries))))
              (p/resolved writer)))]
    (step writer (seq entries))))

(defn- check-chunk-size
  "Fails when `chunk-size` is not a positive integer up to the default budget.
  The option exists to write smaller chunks: a bigger one can pass the
  importer's per-entry cap, and a value that is not a finite number never fills
  a chunk, so both would produce a package that cannot be read."
  [chunk-size]
  (when-not (and (integer? chunk-size)
                 (pos? chunk-size)
                 (<= chunk-size pc/default-chunk-size))
    (throw (js/Error. (str "export: invalid chunk-size " chunk-size
                           ", expected a positive integer no greater than "
                           pc/default-chunk-size)))))

(defn- export
  [state writer progress-fn version chunk-size]
  (when-not (or (= version 1) (= version 2))
    (throw (js/Error. (str "export: invalid version " version ", expected 1 or 2"))))
  (check-chunk-size chunk-size)
  (let [total (count (generate-units state version chunk-size))]
    (letfn [(step [writer units index]
              (if-let [unit (first units)]
                (p/then (write-entries writer ((:entries unit)))
                        (fn [writer]
                          (progress-fn #js {:total total
                                            :item (inc index)
                                            :path (:path unit)})
                          (step writer (next units) (inc index))))
                (p/resolved writer)))]
      (p/then (step writer (seq (generate-units state version chunk-size)) 0)
              (fn [writer]
                (zip/close writer))))))

(defn export-bytes
  ([state]
   (export state (zip/writer (zip/bytes-writer)) noop-fn 1 pc/default-chunk-size))
  ([state options]
   (let [options
         (if (object? options)
           (json/->clj options)
           options)

         progress-fn
         (get options :on-progress noop-fn)

         version
         (get options :version 1)

         chunk-size
         (get options :chunk-size pc/default-chunk-size)]

     (export state (zip/writer (zip/bytes-writer)) progress-fn version chunk-size))))

(defn export-blob
  ([state]
   (export state (zip/writer (zip/blob-writer)) noop-fn 1 pc/default-chunk-size))
  ([state options]
   (let [options
         (if (object? options)
           (json/->clj options)
           options)

         progress-fn
         (get options :on-progress noop-fn)

         version
         (get options :version 1)

         chunk-size
         (get options :chunk-size pc/default-chunk-size)]

     (export state (zip/writer (zip/blob-writer)) progress-fn version chunk-size))))

(defn export-stream
  ([state stream]
   (export state (zip/writer stream) noop-fn 1 pc/default-chunk-size))
  ([state stream options]
   (let [options
         (if (object? options)
           (json/->clj options)
           options)

         progress-fn
         (get options :on-progress noop-fn)

         version
         (get options :version 1)

         chunk-size
         (get options :chunk-size pc/default-chunk-size)]

     (export state (zip/writer stream) progress-fn version chunk-size))))
