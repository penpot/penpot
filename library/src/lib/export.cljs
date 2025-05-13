;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns lib.export
  "A .penpot export implementation"
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.builder :as fb]
   [app.common.json :as json]
   [app.common.schema :as sm]
   [app.common.uuid :as uuid]
   [app.util.object :as obj]
   [app.common.types.color :as types.color]
   [app.common.types.component :as types.component]
   [app.common.types.file :as types.file]
   [app.common.types.page :as types.page]
   [app.common.types.plugins :as ctpg]
   [app.common.types.shape :as types.shape]
   [app.common.types.tokens-lib :as types.tokens-lib]
   [app.common.types.typography :as types.typography]
   [cuerdas.core :as str]
   [app.util.zip :as zip]
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

;; (def encode-media
;;   (sm/encoder ::ctf/media sm/json-transformer))

(def encode-color
  (sm/encoder types.color/schema:color sm/json-transformer))

(def encode-typography
  (sm/encoder types.typography/schema:typography sm/json-transformer))

(def encode-tokens-lib
  (sm/encoder types.tokens-lib/schema:tokens-lib sm/json-transformer))

(def encode-plugin-data
  (sm/encoder ::ctpg/plugin-data sm/json-transformer))

(def ^:private valid-buckets
  #{"file-media-object"
    "team-font-variant"
    "file-object-thumbnail"
    "file-thumbnail"
    "profile"
    "file-data"
    "file-data-fragment"
    "file-change"})

;; FIXME: move to types
(def ^:private schema:storage-object
  [:map {:title "StorageObject"}
   [:id ::sm/uuid]
   [:size ::sm/int]
   [:content-type :string]
   [:bucket [::sm/one-of {:format :string} valid-buckets]]
   [:hash :string]])

(def encode-storage-object
  (sm/encoder schema:storage-object sm/json-transformer))

;; (def encode-file-thumbnail
;;   (sm/encoder schema:file-thumbnail sm/json-transformer))


;; FIXME: naming

(def ^:private file-attrs
  #{:id
    :name
    :migrations
    :features
    :is-shared
    :version})

(defn- generate-file-export-procs
  [{:keys [id data] :as file}]
  ;; (prn "generate-file-export-procs")
  ;; (app.common.pprint/pprint file)
  (cons
   (let [file (cond-> (select-keys file file-attrs)
                (:options data)
                (assoc :options (:options data)))]
     [(str "files/" id ".json")
      (delay (-> file encode-file json/encode))])

   (concat
    (let [pages       (get data :pages)
          pages-index (get data :pages-index)]
      (->> (d/enumerate pages)
           (mapcat
            (fn [[index page-id]]
              (let [path    (str "files/" id "/pages/" page-id ".json")
                    page    (get pages-index page-id)
                    objects (:objects page)
                    page    (-> page
                                (dissoc :objects)
                                (assoc :index index))]
                (cons
                 [(str "files/" id "/pages/" page-id ".json")
                  (delay (-> page encode-page json/encode))]
                 (map (fn [[shape-id shape]]
                        (let [shape (assoc shape :page-id page-id)]
                          [(str "files/" id "/pages/" page-id "/" shape-id ".json")
                           (delay (-> shape encode-shape json/encode))]))
                      objects)))))))

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
                        (encode-tokens-lib tokens-lib)
                        json/encode))])))))

(defn generate-manifest-procs
  [file]
  (let [mdata  {:id (:id file)
                :name (:name file)
                :features (:features file)}
        params {:type "penpot/export-files"
                :version 1
                :generated-by "penpot-lib/develop"
                :files [mdata]
                :relations []}]
    (list
     ["manifest.json" (delay (json/encode params))])))

(defn- export
  [file writer]
  (->> (p/reduce (fn [writer [path proc]]
                   (let [data (deref proc)]
                     (js/console.log "export" path)
                     (->> (zip/add writer path data)
                          (p/fmap (constantly writer)))))

                 writer
                 (concat
                  (generate-manifest-procs @file)
                  (generate-file-export-procs @file)))
       (p/mcat (fn [writer]
                 (zip/close writer)))))

(defn export-bytes
  [file]
  (export file (zip/writer (zip/bytes-writer))))

(defn export-blob
  [file]
  (export file (zip/writer (zip/blob-writer))))

(defn export-stream
  [file stream]
  (export file (zip/writer stream)))
