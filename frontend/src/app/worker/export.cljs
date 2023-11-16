;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.export
  (:require
   [app.common.data :as d]
   [app.common.media :as cm]
   [app.common.text :as ct]
   [app.common.types.components-list :as ctkl]
   [app.common.types.file :as ctf]
   [app.config :as cfg]
   [app.main.features.pointer-map :as fpmap]
   [app.main.render :as r]
   [app.main.repo :as rp]
   [app.util.http :as http]
   [app.util.json :as json]
   [app.util.webapi :as wapi]
   [app.util.zip :as uz]
   [app.worker.impl :as impl]
   [beicon.core :as rx]
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
                          :hasDeletedComponents (d/not-empty? (get-in file [:data :deleted-components]))
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

(def ^:const typography-keys
  [:name :font-family :font-id :font-size :font-style :font-variant-id :font-weight
   :letter-spacing :line-height :text-transform :path])

(def ^:const media-keys
  [:name :mtype :width :height :path])

(defn collect-color
  [result color]
  (collect-entries result color color-keys))

(defn collect-typography
  [result typography]
  (collect-entries result typography typography-keys))

(defn collect-media
  [result media]
  (collect-entries result media media-keys))

(defn parse-library-color
  [[file-id colors]]
  (let [markup
        (->> (vals colors)
             (reduce collect-color {})
             (json/encode))]
    [(str file-id "/colors.json") markup]))

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
        (rx/flat-map
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
  (->> (r/render-components (:data file) :components)
       (rx/map #(vector (str (:id file) "/components.svg") %))))

(defn parse-deleted-components
  [file]
  (->> (r/render-components (:data file) :deleted-components)
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
  (let [detach-text
        (fn [content]
          (->> content
               (ct/transform-nodes
                #(cond-> %
                   (not= file-id (:fill-color-ref-file %))
                   (assoc :fill-color-ref-file file-id)

                   (not= file-id (:typography-ref-file %))
                   (assoc :typography-ref-file file-id)))))

        detach-shape
        (fn [shape]
          (cond-> shape
            (not= file-id (:fill-color-ref-file shape))
            (assoc :fill-color-ref-file file-id)

            (not= file-id (:stroke-color-ref-file shape))
            (assoc :stroke-color-ref-file file-id)

            (not= file-id (:component-file shape))
            (assoc :component-file file-id)

            (= :text (:type shape))
            (update :content detach-text)))

        detach-objects
        (fn [objects]
          (->> objects
               (d/mapm #(detach-shape %2))))

        detach-pages
        (fn [pages-index]
          (->> pages-index
               (d/mapm
                (fn [_ data]
                  (-> data
                      (update :objects detach-objects))))))]
    (-> file
        (update-in [:data :pages-index] detach-pages))))

(defn collect-external-references
  [file]

  (let [get-text-refs
        (fn [content]
          (->> content
               (ct/node-seq #(or (contains? % :fill-color-ref-id)
                                 (contains? % :typography-ref-id)))

               (mapcat (fn [node]
                         (cond-> []
                           (contains? node :fill-color-ref-id)
                           (conj {:id (:fill-color-ref-id node)
                                  :file-id (:fill-color-ref-file node)})

                           (contains? node :typography-ref-id)
                           (conj {:id (:typography-ref-id node)
                                  :file-id (:typography-ref-file node)})
                           )))

               (into [])))

        get-shape-refs
        (fn [[_ shape]]
          (cond-> []
            (contains? shape :fill-color-ref-id)
            (conj {:id (:fill-color-ref-id shape)
                   :file-id (:fill-color-ref-file shape)})

            (contains? shape :stroke-color-ref-id)
            (conj {:id (:stroke-color-ref-id shape)
                   :file-id (:stroke-color-ref-file shape)})

            (contains? shape :component-id)
            (conj {:id (:component-id shape)
                   :file-id (:component-file shape)})

            (= :text (:type shape))
            (into (get-text-refs (:content shape)))))]

    (->> (get-in file [:data :pages-index])
         (vals)
         (mapcat :objects)
         (mapcat get-shape-refs)
         (filter (comp some? :file-id))
         (filter (comp some? :id))
         (group-by :file-id)
         (d/mapm #(mapv :id %2)))))

(defn merge-assets [target-file assets-files]
  (let [external-refs (collect-external-references target-file)

        merge-file-assets
        (fn [target file]
          (let [colors       (-> (get-in file [:data :colors])
                                 (select-keys (get external-refs (:id file))))
                typographies (-> (get-in file [:data :typographies])
                                 (select-keys (get external-refs (:id file))))
                media        (-> (get-in file [:data :media])
                                 (select-keys (get external-refs (:id file))))
                components   (-> (ctkl/components (:data file))
                                 (select-keys (get external-refs (:id file))))]
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

  (case export-type
    :all      files
    :merge    (let [file-list (-> files (d/without-keys [file-id]) vals)]
                (-> (select-keys files [file-id])
                    (update file-id merge-assets file-list)
                    (update file-id make-local-external-references file-id)
                    (update file-id dissoc :libraries)))
    :detach (-> (select-keys files [file-id])
                (update file-id ctf/detach-external-references file-id)
                (update file-id dissoc :libraries))))

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
             (rx/flat-map vals)
             (rx/flat-map process-pages)
             (rx/observe-on :async)
             (rx/flat-map get-page-data)
             (rx/share))

        colors-stream
        (->> files-stream
             (rx/flat-map vals)
             (rx/map #(vector (:id %) (get-in % [:data :colors])))
             (rx/filter #(d/not-empty? (second %)))
             (rx/map parse-library-color))

        typographies-stream
        (->> files-stream
             (rx/flat-map vals)
             (rx/map #(vector (:id %) (get-in % [:data :typographies])))
             (rx/filter #(d/not-empty? (second %)))
             (rx/map parse-library-typographies))

        media-stream
        (->> files-stream
             (rx/flat-map vals)
             (rx/map #(vector (:id %) (get-in % [:data :media])))
             (rx/filter #(d/not-empty? (second %)))
             (rx/flat-map parse-library-media))

        components-stream
        (->> files-stream
             (rx/flat-map vals)
             (rx/filter #(d/not-empty? (ctkl/components-seq (:data %))))
             (rx/flat-map parse-library-components))

        deleted-components-stream
        (->> files-stream
             (rx/flat-map vals)
             (rx/filter #(d/not-empty? (get-in % [:data :deleted-components])))
             (rx/flat-map parse-deleted-components))

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
          (rx/flat-map (fn [[data files]]
                         (->> (uz/compress-files data)
                              (rx/map #(vector (get files file-id) %)))))))))

(defmethod impl/handler :export-binary-file
  [{:keys [files export-type] :as message}]
  (->> (rx/from files)
       (rx/mapcat
        (fn [file]
          (->> (rp/cmd! :export-binfile {:file-id (:id file)
                                         :include-libraries? (= export-type :all)
                                         :embed-assets? (= export-type :merge)})
               (rx/map #(hash-map :type :finish
                                  :file-id (:id file)
                                  :filename (:name file)
                                  :mtype "application/penpot"
                                  :description "Penpot export (*.penpot)"
                                  :uri (wapi/create-uri (wapi/create-blob %))))
               (rx/catch
                (fn [err]
                  (rx/of {:type :error
                          :error (str err)
                          :file-id (:id file)}))))))))

(defmethod impl/handler :export-standard-file
  [{:keys [team-id files export-type features] :as message}]

  (->> (rx/from files)
       (rx/mapcat
        (fn [file]
          (->> (export-file team-id (:id file) export-type features)
               (rx/map
                (fn [value]
                  (if (contains? value :type)
                    value
                    (let [[file export-blob] value]
                      {:type :finish
                       :file-id (:id file)
                       :filename (:name file)
                       :mtype "application/zip"
                       :description "Penpot export (*.zip)"
                       :uri (wapi/create-uri export-blob)}))))
               (rx/catch
                   (fn [err]
                     (rx/of {:type :error
                             :error (str err)
                             :file-id (:id file)}))))))))
