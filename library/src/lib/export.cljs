;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns lib.export
  "A .penpot export implementation"
  (:require
   [app.common.data :as d]
   [app.common.files.builder :as fb]
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

(defn- generate-file-export-procs
  [format {:keys [id data] :as file}]
  (cons
   (let [file (cond-> (select-keys file file-attrs)
                (:options data)
                (assoc :options (:options data)))]
     [(str "files/" id ".json")
      (delay (-> file encode-file json/encode))])

   (concat
    (let [pages       (get data :pages)
          pages-index (get data :pages-index)
          compact?    (= format "compact")]

      (->> (d/enumerate pages)
           (mapcat
            (fn [[index page-id]]
              (let [page    (get pages-index page-id)
                    objects (:objects page)
                    page    (-> page
                                (dissoc :objects)
                                (assoc :index index))]
                (if compact?
                  (let [compacted-objects
                        (reduce-kv
                         (fn [m shape-id shape]
                           (let [shape (-> shape
                                           (cond-> (or (= (:type shape) :path)
                                                       (= (:type shape) :bool))
                                             (update :content vec))
                                           fsc/compact-shape
                                           fsc/round-values
                                           encode-shape)]
                             (assoc m shape-id shape)))
                         {}
                         objects)]
                    (list
                     [(str "files/" id "/pages/" page-id ".json")
                      (delay (-> (assoc page :objects compacted-objects)
                                 json/encode))]))
                  (cons
                   [(str "files/" id "/pages/" page-id ".json")
                    (delay (-> page encode-page json/encode))]
                   (map (fn [[shape-id shape]]
                          [(str "files/" id "/pages/" page-id "/" shape-id ".json")
                           (delay (encode-shape* shape))])
                        objects))))))))

    (->> (get data :components)
         (map (fn [[component-id component]]
                [(str "files/" id "/components/" component-id ".json")
                 (delay (-> component encode-component json/encode))])))

    (->> (get data :colors)
         (map (fn [[color-id color]]
                [(str "files/" id "/colors/" color-id ".json")
                 (delay (let [color (-> color
                                        encode-color
                                        (dissoc :file-id))]
                          (cond-> color
                            (and (contains? color :path)
                                 (str/empty? (:path color)))
                            (dissoc :path)

                            :always
                            (json/encode))))])))

    (->> (get data :typographies)
         (map (fn [[typography-id typography]]
                [(str "files/" id "/typographies/" typography-id ".json")
                 (delay (-> typography
                            encode-typography
                            json/encode))])))

    (when-let [tokens-lib (get data :tokens-lib)]
      (list [(str "files/" id "/tokens.json")
             (delay (-> tokens-lib
                        encode-tokens-lib
                        json/encode))])))))

(defn- generate-files-export-procs
  [state format]
  (->> (vals (get state ::fb/files))
       (mapcat #(generate-file-export-procs format %))))

(defn- generate-media-export-procs
  [state]
  (->> (get state ::fb/file-media)
       (mapcat (fn [[file-media-id file-media]]
                 (let [media-id (get file-media :media-id)
                       media    (get-in state [::fb/media media-id])
                       blob     (get-in state [::fb/blobs media-id])]
                   (list
                    [(str "objects/" media-id (media/mtype->extension (:content-type media)))
                     (delay (get blob :blob))]

                    [(str "objects/" media-id ".json")
                     (delay (-> media
                                ;; FIXME: proper encode?
                                (json/encode)))]
                    [(str "files/" (:file-id file-media) "/media/" file-media-id ".json")
                     (delay (-> file-media
                                (dissoc :file-id)
                                (encode-file-media)
                                (json/encode)))]))))))

(defn- generate-manifest-procs
  [state format]
  (let [opts   (get state :options)
        files  (->> (get state ::fb/files)
                    (mapv (fn [[file-id file]]
                            {:id file-id
                             :name (:name file)
                             :features (:features file)})))
        params {:type "penpot/export-files"
                :version (if (= format "compact") 2 1)
                :generated-by "penpot-library/%version%"
                :referer (get opts :referer)
                :files files
                :relations (->> (:relations state)
                                (mapv vec))}
        params (d/without-nils params)]

    ["manifest.json"
     (delay (json/encode params))]))

(defn- generate-procs
  [state format]
  (let [state (deref state)]
    (cons (generate-manifest-procs state format)
          (concat
           (generate-files-export-procs state format)
           (generate-media-export-procs state)))))

(def ^:private
  xf:add-proc-index
  (map-indexed
   (fn [index proc]
     (conj proc index))))

(def ^:private ^:function noop-fn
  (constantly nil))

(defn- export
  [state writer progress-fn format]
  (let [procs (into [] xf:add-proc-index (generate-procs state format))
        total (count procs)]
    (->> (p/reduce (fn [writer [path data index]]
                     (let [data   (if (delay? data) (deref data) data)
                           report #js {:total total
                                       :item (inc index)
                                       :path path}]
                       (->> (zip/add writer path data)
                            (p/fmap (fn [_]
                                      (progress-fn report)
                                      writer)))))
                   writer
                   procs)
         (p/mcat (fn [writer]
                   (zip/close writer))))))

(defn export-bytes
  ([state]
   (export state (zip/writer (zip/bytes-writer)) noop-fn "legacy"))
  ([state options]
   (let [options
         (if (object? options)
           (json/->clj options)
           options)

         progress-fn
         (get options :on-progress noop-fn)

         format
         (get options :format "legacy")]

     (export state (zip/writer (zip/bytes-writer)) progress-fn format))))

(defn export-blob
  ([state]
   (export state (zip/writer (zip/blob-writer)) noop-fn "legacy"))
  ([state options]
   (let [options
         (if (object? options)
           (json/->clj options)
           options)

         progress-fn
         (get options :on-progress noop-fn)

         format
         (get options :format "legacy")]

     (export state (zip/writer (zip/blob-writer)) progress-fn format))))

(defn export-stream
  ([state stream]
   (export state (zip/writer stream) noop-fn "legacy"))
  ([state stream options]
   (let [options
         (if (object? options)
           (json/->clj options)
           options)

         progress-fn
         (get options :on-progress noop-fn)

         format
         (get options :format "legacy")]

     (export state (zip/writer stream) progress-fn format))))
