;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.export
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.json :as json]
   [app.common.media :as cm]
   [app.common.text :as ct]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.config :as cfg]
   [app.main.features.pointer-map :as fpmap]
   [app.main.render :as r]
   [app.main.repo :as rp]
   [app.util.http :as http]
   [app.util.webapi :as wapi]
   [app.util.zip :as uz]
   [app.worker.impl :as impl]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]))

(def ^:const current-version 2)

(defn create-manifest
  "Creates a manifest entry for the given files"
  [team-id file-id export-type files features]
  (letfn [(format-page [manifest page]
            (-> manifest
                (assoc (str (:id page))
                       {:name (:name page)})))

          (format-file [manifest file]
            (let [name      (:name file)
                  is-shared (:is-shared file)
                  pages     (->> (get-in file [:data :pages])
                                 (mapv str))
                  index     (->> (get-in file [:data :pages-index])
                                 (vals)
                                 (reduce format-page {}))]
              (-> manifest
                  (assoc (str (:id file))
                         {:name                 name
                          :features             features
                          :shared               is-shared
                          :pages                pages
                          :pagesIndex           index
                          :version              current-version
                          :libraries            (->> (:libraries file) (into #{}) (mapv str))
                          :exportType           (d/name export-type)
                          :hasComponents        (d/not-empty? (ctkl/components-seq (:data file)))
                          :hasDeletedComponents (d/not-empty? (ctkl/deleted-components-seq (:data file)))
                          :hasMedia             (d/not-empty? (get-in file [:data :media]))
                          :hasColors            (d/not-empty? (get-in file [:data :colors]))
                          :hasTypographies      (d/not-empty? (get-in file [:data :typographies]))}))))]
    (let [manifest {:teamId (str team-id)
                    :fileId (str file-id)
                    :files (->> (vals files) (reduce format-file {}))}]
      (json/encode manifest))))

(defn process-pages [file]
  (let [pages (get-in file [:data :pages])
        pages-index (get-in file [:data :pages-index])]
    (->> pages
         (map #(hash-map
                :file-id (:id file)
                :data (get pages-index %))))))

(defn get-page-data
  [{file-id :file-id {:keys [id name] :as data} :data}]
  (->> (r/render-page data)
       (rx/map (fn [markup]
                 {:id id
                  :name name
                  :file-id file-id
                  :markup markup}))))

(defn collect-page
  [{:keys [id file-id markup] :as page}]
  [(str file-id "/" id ".svg") markup])

(defn collect-entries [result data keys]
  (-> result
      (assoc (str (:id data))
             (->> (select-keys data keys)
                  (d/deep-mapm
                   (fn [[k v]]
                     [(-> k str/camel) v]))))))

(def ^:const color-keys
  [:name :color :opacity :gradient :path])

(def ^:const image-color-keys
  [:width :height :mtype :name :keep-aspect-ratio])

(def ^:const typography-keys
  [:name :font-family :font-id :font-size :font-style :font-variant-id :font-weight
   :letter-spacing :line-height :text-transform :path])

(def ^:const media-keys
  [:name :mtype :width :height :path])

(defn collect-color
  [result color]
  (let [id               (str (:id color))
        basic-data       (select-keys color color-keys)
        image-color-data (when-let [image-color (:image color)]
                           (->> (select-keys image-color image-color-keys)))
        color-data       (cond-> basic-data
                           (some? image-color-data)
                           (->
                            (assoc :image image-color-data)
                            (assoc-in [:image :id] (str (get-in color [:image :id])))))]
    (-> result
        (assoc id
               (->> color-data
                    (d/deep-mapm
                     (fn [[k v]]
                       [(-> k str/camel) v])))))))

(defn collect-typography
  [result typography]
  (collect-entries result typography typography-keys))

(defn collect-media
  [result media]
  (collect-entries result media media-keys))

(defn parse-library-color
  [[file-id colors]]
  (rx/merge
   (let [markup
         (->> (vals colors)
              (reduce collect-color {})
              (json/encode))]
     (rx/of (vector (str file-id "/colors.json") markup)))

   (->> (rx/from (vals colors))
        (rx/map :image)
        (rx/filter d/not-empty?)
        (rx/merge-map
         (fn [image-color]
           (let [file-path (str/concat file-id "/colors/" (:id image-color) (cm/mtype->extension (:mtype image-color)))]
             (->> (http/send!
                   {:uri (cfg/resolve-file-media image-color)
                    :response-type :blob
                    :method :get})
                  (rx/map :body)
                  (rx/map #(vector file-path %)))))))))

(defn parse-library-typographies
  [[file-id typographies]]
  (let [markup
        (->> (vals typographies)
             (reduce collect-typography {})
             (json/encode))]
    [(str file-id "/typographies.json") markup]))

(defn parse-library-media
  [[file-id media]]
  (rx/merge
   (let [markup
         (->> (vals media)
              (reduce collect-media {})
              (json/encode))]
     (rx/of (vector (str file-id "/media.json") markup)))

   (->> (rx/from (vals media))
        (rx/map #(assoc % :file-id file-id))
        (rx/merge-map
         (fn [media]
           (let [file-path (str/concat file-id "/media/" (:id media) (cm/mtype->extension (:mtype media)))]
             (->> (http/send!
                   {:uri (cfg/resolve-file-media media)
                    :response-type :blob
                    :method :get})
                  (rx/map :body)
                  (rx/map #(vector file-path %)))))))))

(defn parse-library-components
  [file]
  (->> (r/render-components (:data file) false)
       (rx/map #(vector (str (:id file) "/components.svg") %))))

(defn parse-deleted-components
  [file]
  (->> (r/render-components (:data file) true)
       (rx/map #(vector (str (:id file) "/deleted-components.svg") %))))

(defn fetch-file-with-libraries
  [file-id features]
  (->> (rx/zip (->> (rp/cmd! :get-file {:id file-id :features features})
                    (rx/mapcat fpmap/resolve-file))
               (rp/cmd! :get-file-libraries {:file-id file-id}))
       (rx/map
        (fn [[file file-libraries]]
          (let [libraries-ids (->> file-libraries (map :id) (filterv #(not= (:id file) %)))]
            (assoc file :libraries libraries-ids))))))

(defn make-local-external-references
  [file file-id]
  (let [change-fill
        (fn [fill]
          (cond-> fill
            (not= file-id (:fill-color-ref-file fill))
            (assoc :fill-color-ref-file file-id)))

        change-stroke
        (fn [stroke]
          (cond-> stroke
            (not= file-id (:stroke-color-ref-file stroke))
            (assoc :stroke-color-ref-file file-id)))

        change-text
        (fn [content]
          (->> content
               (ct/transform-nodes
                (fn [node]
                  (-> node
                      (d/update-when :fills #(mapv change-fill %))
                      (cond-> (not= file-id (:typography-ref-file node))
                        (assoc :typography-ref-file file-id)))))))

        change-shape
        (fn [shape]
          (-> shape
              (d/update-when :fills #(mapv change-fill %))
              (d/update-when :strokes #(mapv change-stroke %))
              (cond-> (not= file-id (:component-file shape))
                (assoc :component-file file-id))

              (cond-> (= :text (:type shape))
                (update :content change-text))))

        change-objects
        (fn [objects]
          (->> objects
               (d/mapm #(change-shape %2))))

        change-pages
        (fn [pages-index]
          (->> pages-index
               (d/mapm
                (fn [_ data]
                  (-> data
                      (update :objects change-objects))))))]
    (-> file
        (update-in [:data :pages-index] change-pages))))

(defn merge-assets [target-file assets-files]
  (let [merge-file-assets
        (fn [target file]
          (let [colors       (get-in file [:data :colors])
                typographies (get-in file [:data :typographies])
                media        (get-in file [:data :media])
                components   (ctkl/components (:data file))]
            (cond-> target
              (d/not-empty? colors)
              (update-in [:data :colors] merge colors)

              (d/not-empty? typographies)
              (update-in [:data :typographies] merge typographies)

              (d/not-empty? media)
              (update-in [:data :media] merge media)

              (d/not-empty? components)
              (update-in [:data :components] merge components))))]

    (->> assets-files
         (reduce merge-file-assets target-file))))

(defn process-export
  [file-id export-type files]

  (let [result
        (case export-type
          :all      files
          :merge    (let [file-list (-> files (d/without-keys [file-id]) vals)]
                      (-> (select-keys files [file-id])
                          (update file-id merge-assets file-list)
                          (update file-id make-local-external-references file-id)
                          (update file-id dissoc :libraries)))
          :detach (-> (select-keys files [file-id])
                      (update file-id ctf/detach-external-references file-id)
                      (update file-id dissoc :libraries)))]

    ;;(.log js/console (clj->js result))
    result))

(defn collect-files
  [file-id export-type features]
  (letfn [(fetch-dependencies [[files pending]]
            (if (empty? pending)
              ;; When not pending, we finish the generation
              (rx/empty)

              ;; Still pending files, fetch the next one
              (let [next    (peek pending)
                    pending (pop pending)]
                (if (contains? files next)
                  ;; The file is already in the result
                  (rx/of [files pending])

                  (->> (fetch-file-with-libraries next features)
                       (rx/map
                        (fn [file]
                          [(-> files
                               (assoc (:id file) file))
                           (as-> pending $
                             (reduce conj $ (:libraries file)))])))))))]
    (let [files {}
          pending [file-id]]
      (->> (rx/of [files pending])
           (rx/expand fetch-dependencies)
           (rx/last)
           (rx/map first)
           (rx/map #(process-export file-id export-type %))))))

(defn export-file
  [team-id file-id export-type features]
  (let [files-stream (->> (collect-files file-id export-type features)
                          (rx/share))

        manifest-stream
        (->> files-stream
             (rx/map #(create-manifest team-id file-id export-type % features))
             (rx/map #(vector "manifest.json" %)))

        render-stream
        (->> files-stream
             (rx/merge-map vals)
             (rx/merge-map process-pages)
             (rx/observe-on :async)
             (rx/merge-map get-page-data)
             (rx/share))

        colors-stream
        (->> files-stream
             (rx/merge-map vals)
             (rx/map #(vector (:id %) (get-in % [:data :colors])))
             (rx/filter #(d/not-empty? (second %)))
             (rx/merge-map parse-library-color))

        typographies-stream
        (->> files-stream
             (rx/merge-map vals)
             (rx/map #(vector (:id %) (get-in % [:data :typographies])))
             (rx/filter #(d/not-empty? (second %)))
             (rx/map parse-library-typographies))

        media-stream
        (->> files-stream
             (rx/merge-map vals)
             (rx/map #(vector (:id %) (get-in % [:data :media])))
             (rx/filter #(d/not-empty? (second %)))
             (rx/merge-map parse-library-media))

        components-stream
        (->> files-stream
             (rx/merge-map vals)
             (rx/filter #(d/not-empty? (ctkl/components-seq (:data %))))
             (rx/merge-map parse-library-components))

        deleted-components-stream
        (->> files-stream
             (rx/merge-map vals)
             (rx/filter #(d/not-empty? (ctkl/deleted-components-seq (:data %))))
             (rx/merge-map parse-deleted-components))

        pages-stream
        (->> render-stream
             (rx/map collect-page))]

    (rx/merge
     (->> render-stream
          (rx/map #(hash-map
                    :type :progress
                    :file file-id
                    :data (str "Render " (:file-name %) " - " (:name %)))))

     (->> (rx/merge
           manifest-stream
           pages-stream
           components-stream
           deleted-components-stream
           media-stream
           colors-stream
           typographies-stream)
          (rx/reduce conj [])
          (rx/with-latest-from files-stream)
          (rx/merge-map (fn [[data files]]
                          (->> (uz/compress-files data)
                               (rx/map #(vector (get files file-id) %)))))))))

(defmethod impl/handler :export-files
  [{:keys [team-id files type format features] :as message}]
  (cond
    (or (= format :binfile-v1)
        (= format :binfile-v3))
    (->> (rx/from files)
         (rx/mapcat
          (fn [file]
            (->> (rp/cmd! :export-binfile {:file-id (:id file)
                                           :version (if (= format :binfile-v3) 3 1)
                                           :include-libraries (= type :all)
                                           :embed-assets (= type :merge)})
                 (rx/map wapi/create-blob)
                 (rx/map wapi/create-uri)
                 (rx/map (fn [uri]
                           {:type :finish
                            :file-id (:id file)
                            :filename (:name file)
                            :mtype (if (= format :binfile-v3)
                                     "application/zip"
                                     "application/penpot")
                            :uri uri}))
                 (rx/catch
                  (fn [cause]
                    (rx/of (ex/raise :type :internal
                                     :code :export-error
                                     :hint "unexpected error on exporting file"
                                     :file-id (:id file)
                                     :cause cause))))))))

    (= format :legacy-zip)
    (->> (rx/from files)
         (rx/mapcat
          (fn [file]
            (->> (export-file team-id (:id file) type features)
                 (rx/map
                  (fn [value]
                    (if (contains? value :type)
                      value
                      (let [[file export-blob] value]
                        {:type :finish
                         :file-id (:id file)
                         :filename (:name file)
                         :mtype "application/zip"
                         :uri (wapi/create-uri export-blob)}))))
                 (rx/catch
                  (fn [cause]
                    (rx/of (ex/raise :type :internal
                                     :code :export-error
                                     :hint "unexpected error on exporting file"
                                     :file-id (:id file)
                                     :cause cause))))))))))
