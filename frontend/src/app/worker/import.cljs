;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.import
  (:refer-clojure :exclude [resolve])
  (:require
   ["jszip" :as zip]
   [app.common.data :as d]
   [app.common.files.builder :as fb]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as gpa]
   [app.common.logging :as log]
   [app.common.media :as cm]
   [app.common.pprint :as pp]
   [app.common.text :as ct]
   [app.common.uuid :as uuid]
   [app.main.repo :as rp]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.json :as json]
   [app.util.sse :as sse]
   [app.util.webapi :as wapi]
   [app.util.zip :as uz]
   [app.worker.impl :as impl]
   [app.worker.import.parser :as parser]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [tubax.core :as tubax]))

(log/set-level! :warn)

;; Upload changes batches size
(def ^:const change-batch-size 100)

(def conjv (fnil conj []))

(defn get-file
  "Resolves the file inside the context given its id and the data"
  ([context type]
   (get-file context type nil nil))

  ([context type id]
   (get-file context type id nil))

  ([context type id media]
   (let [file-id (:file-id context)
         path (case type
                :manifest           (str "manifest.json")
                :page               (str file-id "/" id ".svg")
                :colors             (str file-id "/colors.json")
                :typographies       (str file-id "/typographies.json")
                :media-list         (str file-id "/media.json")
                :media              (let [ext (cm/mtype->extension (:mtype media))]
                                      (str/concat file-id "/media/" id ext))
                :components         (str file-id "/components.svg")
                :deleted-components (str file-id "/deleted-components.svg"))

         parse-svg?  (and (not= type :media) (str/ends-with? path "svg"))
         parse-json? (and (not= type :media) (str/ends-with? path "json"))
         no-parse?   (or (= type :media)
                         (not (or parse-svg? parse-json?)))

         file-type (if (or parse-svg? parse-json?) "text" "blob")]

     (log/debug :action "parsing" :path path)

     (cond->> (uz/get-file (:zip context) path file-type)
       parse-svg?
       (rx/map (comp tubax/xml->clj :content))

       parse-json?
       (rx/map (comp json/decode :content))

       no-parse?
       (rx/map :content)))))

(defn progress!
  ([context type]
   (assert (keyword? type))
   (progress! context type nil nil nil))

  ([context type file]
   (assert (keyword? type))
   (assert (string? file))
   (progress! context type file nil nil))

  ([context type current total]
   (assert (keyword? type))
   (assert (number? current))
   (assert (number? total))
   (progress! context type nil current total))

  ([context type file current total]
   (when (and context (contains? context :progress))
     (let [msg {:type type
                :file file
                :current current
                :total total}]
       (log/debug :status :import-progress :message msg)
       (rx/push! (:progress context) {:file-id (:file-id context)
                                      :status :import-progress
                                      :message msg})))))

(defn resolve-factory
  "Creates a wrapper around the atom to remap ids to new ids and keep
  their relationship so they ids are coherent."
  []
  (let [id-mapping-atom (atom {})
        resolve
        (fn [id-mapping id]
          (assert (uuid? id) (str id))
          (get id-mapping id))

        set-id
        (fn [id-mapping id]
          (assert (uuid? id) (str id))
          (cond-> id-mapping
            (nil? (resolve id-mapping id))
            (assoc id (uuid/next))))]

    (fn [id]
      (when (some? id)
        (swap! id-mapping-atom set-id id)
        (resolve @id-mapping-atom id)))))

(defn create-file
  "Create a new file on the back-end"
  [context features]
  (let [resolve-fn (:resolve context)
        file-id    (resolve-fn (:file-id context))]
    (rp/cmd! :create-temp-file
             {:id file-id
              :name (:name context)
              :is-shared (:shared context)
              :project-id (:project-id context)
              :create-page false

              ;; If the features object exists send that. Otherwise we remove the components/v2 because
              ;; if the features attribute doesn't exist is a version < 2.0. The other features will
              ;; be kept so the shapes are created full featured
              :features (d/nilv (:features context) (disj features "components/v2"))})))

(defn link-file-libraries
  "Create a new file on the back-end"
  [context]
  (let [resolve (:resolve context)
        file-id (resolve (:file-id context))
        libraries (->> context :libraries (mapv resolve))]
    (->> (rx/from libraries)
         (rx/map #(hash-map :file-id file-id :library-id %))
         (rx/merge-map (partial rp/cmd! :link-file-to-library)))))

(defn send-changes
  "Creates batches of changes to be sent to the backend"
  [context file]
  (let [file-id    (:id file)
        session-id (uuid/next)
        changes    (fb/generate-changes file)
        batches    (->> changes
                        (partition change-batch-size change-batch-size nil)
                        (mapv vec))

        processed  (atom 0)
        total      (count batches)]

    (rx/concat
     (->> (rx/from (d/enumerate batches))
          (rx/merge-map
           (fn [[i change-batch]]
             (->> (rp/cmd! :update-temp-file
                           {:id file-id
                            :session-id session-id
                            :revn i
                            :changes change-batch})
                  (rx/tap #(do (swap! processed inc)
                               (progress! context :upload-data @processed total))))))
          (rx/map first)
          (rx/ignore))

     (->> (rp/cmd! :persist-temp-file {:id file-id})
          ;; We use merge to keep some information not stored in back-end
          (rx/map #(merge file %))))))

(defn upload-media-files
  "Upload a image to the backend and returns its id"
  [context file-id name data-uri]

  (log/debug :action "Uploading" :file-id file-id :name name)

  (->> (http/send!
        {:uri data-uri
         :response-type :blob
         :method :get})
       (rx/map :body)
       (rx/map
        (fn [blob]
          {:name name
           :file-id file-id
           :content blob
           :is-local true}))
       (rx/tap #(progress! context :upload-media name))
       (rx/merge-map #(rp/cmd! :upload-file-media-object %))))

(defn resolve-text-content
  [node context]
  (let [resolve (:resolve context)]
    (->> node
         (ct/transform-nodes
          (fn [item]
            (cond-> item
              (uuid? (get item :fill-color-ref-id))
              (d/update-when :fill-color-ref-id resolve)

              (uuid? (get item :fill-color-ref-file))
              (d/update-when :fill-color-ref-file resolve)

              (uuid? (get item :typography-ref-id))
              (d/update-when :typography-ref-id resolve)

              (uuid? (get item :typography-ref-file))
              (d/update-when :typography-ref-file resolve)))))))

(defn resolve-data-ids
  [data type context]
  (let [resolve (:resolve context)]
    (-> data
        (d/update-when :fill-color-ref-id resolve)
        (d/update-when :fill-color-ref-file resolve)
        (d/update-when :stroke-color-ref-id resolve)
        (d/update-when :stroke-color-ref-file resolve)
        (d/update-when :component-id resolve)
        (d/update-when :component-file resolve)
        (d/update-when :shape-ref resolve)

        (cond-> (= type :text)
          (d/update-when :content resolve-text-content context))

        (cond-> (and (= type :frame) (= :grid (:layout data)))
          (update
           :layout-grid-cells
           (fn [cells]
             (->> (vals cells)
                  (reduce (fn [cells {:keys [id shapes]}]
                            (assoc-in cells [id :shapes] (mapv resolve shapes)))
                          cells))))))))

(defn- translate-frame
  [data type file]
  (let [frame-id (:current-frame-id file)
        frame (when (and (some? frame-id) (not= frame-id uuid/zero))
                (fb/lookup-shape file frame-id))]

    (if (some? frame)
      (-> data
          (d/update-when :x + (:x frame))
          (d/update-when :y + (:y frame))
          (cond-> (= :path type)
            (update :content gpa/move-content (gpt/point (:x frame) (:y frame)))))

      data)))

(defn process-import-node
  [context file node]
  (let [type         (parser/get-type node)
        close?       (parser/close? node)]
    (if close?
      (case type
        :frame    (fb/close-artboard file)
        :group    (fb/close-group file)
        :bool     (fb/close-bool file)
        :svg-raw  (fb/close-svg-raw file)
        #_default file)

      (let [resolve      (:resolve context)
            old-id       (parser/get-id node)
            interactions (->> (parser/parse-interactions node)
                              (mapv #(update % :destination resolve)))

            data         (-> (parser/parse-data type node)
                             (resolve-data-ids type context)
                             (cond-> (some? old-id)
                               (assoc :id (resolve old-id)))
                             (cond-> (< (:version context 1) 2)
                               (translate-frame type file)))]
        (try
          (let [file (case type
                       :frame    (fb/add-artboard   file data)
                       :group    (fb/add-group      file data)
                       :bool     (fb/add-bool       file data)
                       :rect     (fb/create-rect    file data)
                       :circle   (fb/create-circle  file data)
                       :path     (fb/create-path    file data)
                       :text     (fb/create-text    file data)
                       :image    (fb/create-image   file data)
                       :svg-raw  (fb/create-svg-raw file data)
                       #_default file)]

            ;; We store this data for post-processing after every shape has been
            ;; added
            (cond-> file
              (d/not-empty? interactions)
              (assoc-in [:interactions (:id data)] interactions)))

          (catch :default err
            (log/error :hint (ex-message err) :cause err :js/data data)
            (update file :errors conjv data)))))))

(defn setup-interactions
  [file]
  (letfn [(add-interactions
            [file [id interactions]]
            (->> interactions
                 (reduce #(fb/add-interaction %1 id %2) file)))

          (process-interactions
            [file]
            (let [interactions (:interactions file)
                  file (dissoc file :interactions)]
              (->> interactions (reduce add-interactions file))))]
    (-> file process-interactions)))

(defn resolve-media
  [context file-id node]
  (if (or (and (not (parser/close? node))
               (parser/has-image? node))
          (parser/has-stroke-images? node)
          (parser/has-fill-images? node))
    (let [name               (parser/get-image-name node)
          has-image          (parser/has-image? node)
          image-data         (parser/get-image-data node)
          image-fill         (parser/get-image-fill node)
          fill-images-data   (->> (parser/get-fill-images-data node)
                                  (map #(assoc % :type :fill)))
          stroke-images-data (->> (parser/get-stroke-images-data node)
                                  (map #(assoc % :type :stroke)))

          images-data        (concat
                              fill-images-data
                              stroke-images-data
                              (when has-image
                                [{:href image-data}]))]
      (->> (rx/from images-data)
           (rx/mapcat (fn [image-data]
                        (->> (upload-media-files context file-id name (:href image-data))
                             (rx/catch #(do (.error js/console "Error uploading media: " name)
                                            (rx/of node)))
                             (rx/map (fn [data]
                                       (let [data
                                             (cond-> data
                                               (some? (:keep-aspect-ratio image-data))
                                               (assoc :keep-aspect-ratio (:keep-aspect-ratio image-data)))]
                                         [(:id image-data) data]))))))
           (rx/reduce (fn [acc [id data]] (assoc acc id data)) {})
           (rx/map
            (fn [images]
              (let [media (get images nil)]
                (-> node
                    (assoc :images images)
                    (cond-> (some? media)
                      (->
                       (assoc-in [:attrs :penpot:media-id]     (:id media))
                       (assoc-in [:attrs :penpot:media-width]  (:width media))
                       (assoc-in [:attrs :penpot:media-height] (:height media))
                       (assoc-in [:attrs :penpot:media-mtype]  (:mtype media))
                       (cond-> (some? (:keep-aspect-ratio media))
                         (assoc-in [:attrs :penpot:media-keep-aspect-ratio] (:keep-aspect-ratio media)))
                       (assoc-in [:attrs :penpot:fill-color]           (:fill image-fill))
                       (assoc-in [:attrs :penpot:fill-color-ref-file]  (:fill-color-ref-file image-fill))
                       (assoc-in [:attrs :penpot:fill-color-ref-id]    (:fill-color-ref-id image-fill))
                       (assoc-in [:attrs :penpot:fill-opacity]         (:fill-opacity image-fill))
                       (assoc-in [:attrs :penpot:fill-color-gradient]  (:fill-color-gradient image-fill))))))))))

    ;; If the node is not an image just return the node
    (->> (rx/of node)
         (rx/observe-on :async))))

(defn media-node? [node]
  (or (and (parser/shape? node)
           (parser/has-image? node)
           (not (parser/close? node)))
      (parser/has-stroke-images? node)
      (parser/has-fill-images? node)))

(defn import-page
  [context file [page-id page-name content]]
  (let [nodes (->> content parser/node-seq)
        file-id (:id file)
        resolve (:resolve context)
        page-data (-> (parser/parse-page-data content)
                      (assoc :name page-name)
                      (assoc :id (resolve page-id)))
        flows     (->> (get-in page-data [:options :flows])
                       (mapv #(update % :starting-frame resolve)))

        guides    (-> (get-in page-data [:options :guides])
                      (update-vals #(update % :frame-id resolve)))

        page-data (-> page-data
                      (d/assoc-in-when [:options :flows] flows)
                      (d/assoc-in-when [:options :guides] guides))
        file      (-> file (fb/add-page page-data))

        ;; Preprocess nodes to parallel upload the images. Store the result in a table
        ;; old-node => node with image
        ;; that will be used in the second pass immediately
        pre-process-images
        (->> (rx/from nodes)
             (rx/filter media-node?)
             ;; TODO: this should be merge-map, but we disable the
             ;; parallel upload until we resolve resource usage issues
             ;; on backend.
             (rx/mapcat
              (fn [node]
                (->> (resolve-media context file-id node)
                     (rx/map (fn [result]
                               [node result])))))
             (rx/reduce conj {}))]

    (->> pre-process-images
         (rx/merge-map
          (fn  [pre-proc]
            (->> (rx/from nodes)
                 (rx/filter parser/shape?)
                 (rx/map (fn [node] (or (get pre-proc node) node)))
                 (rx/reduce (partial process-import-node context) file)
                 (rx/map (comp fb/close-page setup-interactions))))))))

(defn import-component [context file node]
  (let [resolve            (:resolve context)
        content            (parser/find-node node :g)
        file-id            (:id file)
        old-id             (parser/get-id node)
        id                 (resolve old-id)
        path               (get-in node [:attrs :penpot:path] "")
        type               (parser/get-type content)
        main-instance-id   (resolve (uuid (get-in node [:attrs :penpot:main-instance-id] "")))
        main-instance-page (resolve (uuid (get-in node [:attrs :penpot:main-instance-page] "")))
        data               (-> (parser/parse-data type content)
                               (assoc :path path)
                               (assoc :id id)
                               (assoc :main-instance-id main-instance-id)
                               (assoc :main-instance-page main-instance-page))

        file               (-> file (fb/start-component data type))
        children           (parser/node-seq node)]

    (->> (rx/from children)
         (rx/filter parser/shape?)
         (rx/skip 1)       ;; Skip the outer component and the respective closint tag
         (rx/skip-last 1)  ;; because they are handled in start-component an finish-component
         (rx/mapcat (partial resolve-media context file-id))
         (rx/reduce (partial process-import-node context) file)
         (rx/map fb/finish-component))))

(defn import-deleted-component [context file node]
  (let [resolve            (:resolve context)
        content            (parser/find-node node :g)
        file-id            (:id file)
        old-id             (parser/get-id node)
        id                 (resolve old-id)
        path               (get-in node [:attrs :penpot:path] "")
        main-instance-id   (resolve (uuid (get-in node [:attrs :penpot:main-instance-id] "")))
        main-instance-page (resolve (uuid (get-in node [:attrs :penpot:main-instance-page] "")))
        main-instance-x    (get-in node [:attrs :penpot:main-instance-x] "")
        main-instance-y    (get-in node [:attrs :penpot:main-instance-y] "")
        type               (parser/get-type content)

        data (-> (parser/parse-data type content)
                 (assoc :path path)
                 (assoc :id id)
                 (assoc :main-instance-id main-instance-id)
                 (assoc :main-instance-page main-instance-page)
                 (assoc :main-instance-x main-instance-x)
                 (assoc :main-instance-y main-instance-y))

        file         (-> file (fb/start-component data))
        component-id (:current-component-id file)
        children     (parser/node-seq node)]

    (->> (rx/from children)
         (rx/filter parser/shape?)
         (rx/skip 1)
         (rx/skip-last 1)
         (rx/mapcat (partial resolve-media context file-id))
         (rx/reduce (partial process-import-node context) file)
         (rx/map fb/finish-component)
         (rx/map (partial fb/finish-deleted-component
                          component-id
                          main-instance-page
                          main-instance-x
                          main-instance-y)))))

(defn process-pages
  [context file]
  (let [index (:pages-index context)
        get-page-data
        (fn [page-id]
          [page-id (get-in index [page-id :name])])

        pages (->> (:pages context) (mapv get-page-data))]

    (->> (rx/from pages)
         (rx/tap (fn [[_ page-name]]
                   (progress! context :process-page page-name)))
         (rx/mapcat
          (fn [[page-id page-name]]
            (->> (get-file context :page page-id)
                 (rx/map (fn [page-data] [page-id page-name page-data])))))
         (rx/concat-reduce (partial import-page context) file))))

(defn process-library-colors
  [context file]
  (if (:has-colors context)
    (let [resolve (:resolve context)
          add-color
          (fn [file [id color]]
            (let [color (-> color
                            (d/update-in-when [:gradient :type] keyword)
                            (assoc :id (resolve id)))]
              (fb/add-library-color file color)))]
      (->> (get-file context :colors)
           (rx/merge-map (comp d/kebab-keys parser/string->uuid))
           (rx/reduce add-color file)))

    (rx/of file)))

(defn process-library-typographies
  [context file]
  (if (:has-typographies context)
    (let [resolve (:resolve context)]
      (->> (get-file context :typographies)
           (rx/merge-map (comp d/kebab-keys parser/string->uuid))
           (rx/map (fn [[id typography]]
                     (-> typography
                         (d/kebab-keys)
                         (assoc :id (resolve id)))))
           (rx/reduce fb/add-library-typography file)))

    (rx/of file)))

(defn process-library-media
  [context file]
  (if (:has-media context)
    (let [resolve (:resolve context)]
      (->> (get-file context :media-list)
           (rx/merge-map (comp d/kebab-keys parser/string->uuid))
           (rx/mapcat
            (fn [[id media]]
              (let [media (-> media
                              (assoc :id (resolve id))
                              (update :name str))]
                (->> (get-file context :media id media)
                     (rx/map (fn [blob]
                               (let [content (.slice blob 0 (.-size blob) (:mtype media))]
                                 {:name (:name media)
                                  :id (:id media)
                                  :file-id (:id file)
                                  :content content
                                  :is-local false})))
                     (rx/tap #(progress! context :upload-media (:name %)))
                     (rx/merge-map #(rp/cmd! :upload-file-media-object %))
                     (rx/map (constantly media))
                     (rx/catch #(do (.error js/console (str "Error uploading media: " (:name media)))
                                    (rx/empty)))))))
           (rx/reduce fb/add-library-media file)))
    (rx/of file)))

(defn process-library-components
  [context file]
  (if (:has-components context)
    (let [split-components
          (fn [content] (->> (parser/node-seq content)
                             (filter #(= :symbol (:tag %)))))]

      (->> (get-file context :components)
           (rx/merge-map split-components)
           (rx/concat-reduce (partial import-component context) file)))
    (rx/of file)))

(defn process-deleted-components
  [context file]
  (if (:has-deleted-components context)
    (let [split-components
          (fn [content] (->> (parser/node-seq content)
                             (filter #(= :symbol (:tag %)))))]

      (->> (get-file context :deleted-components)
           (rx/merge-map split-components)
           (rx/concat-reduce (partial import-deleted-component context) file)))
    (rx/of file)))

(defn process-file
  [context file]

  (let [progress-str (rx/subject)
        context (assoc context :progress progress-str)]
    [progress-str
     (->> (rx/of file)
          (rx/merge-map (partial process-pages context))
          (rx/tap #(progress! context :process-colors))
          (rx/merge-map (partial process-library-colors context))
          (rx/tap #(progress! context :process-typographies))
          (rx/merge-map (partial process-library-typographies context))
          (rx/tap #(progress! context :process-media))
          (rx/merge-map (partial process-library-media context))
          (rx/tap #(progress! context :process-components))
          (rx/merge-map (partial process-library-components context))
          (rx/tap #(progress! context :process-deleted-components))
          (rx/merge-map (partial process-deleted-components context))
          (rx/merge-map (partial send-changes context))
          (rx/tap #(rx/end! progress-str)))]))

(defn create-files
  [{:keys [system-features] :as context} files]

  (let [data (group-by :file-id files)]
    (rx/concat
     (->> (rx/from files)
          (rx/map #(merge context %))
          (rx/merge-map (fn [context]
                          (->> (create-file context system-features)
                               (rx/map #(vector % (first (get data (:file-id context)))))))))

     (->> (rx/from files)
          (rx/map #(merge context %))
          (rx/merge-map link-file-libraries)
          (rx/ignore)))))

(defn parse-mtype [ba]
  (let [u8 (js/Uint8Array. ba 0 4)
        sg (areduce u8 i ret "" (str ret (if (zero? i) "" " ") (.toString (aget u8 i) 8)))]
    (case sg
      "120 113 3 4" "application/zip"
      "1 13 32 206" "application/octet-stream"
      "other")))

(defmethod impl/handler :analyze-import
  [{:keys [files features]}]

  (->> (rx/from files)
       (rx/merge-map
        (fn [file]
          (let [st (->> (http/send!
                         {:uri (:uri file)
                          :response-type :blob
                          :method :get})
                        (rx/map :body)
                        (rx/mapcat wapi/read-file-as-array-buffer)
                        (rx/map (fn [data]
                                  {:type (parse-mtype data)
                                   :uri (:uri file)
                                   :body data})))]
            (->> (rx/merge
                  (->> st
                       (rx/filter (fn [data] (= "application/zip" (:type data))))
                       (rx/merge-map #(zip/loadAsync (:body %)))
                       (rx/merge-map #(get-file {:zip %} :manifest))
                       (rx/map (comp d/kebab-keys parser/string->uuid))
                       (rx/map
                        (fn [data]
                          ;; Checks if the file is exported with components v2 and the current team only
                          ;; supports components v1
                          (let [has-file-v2?
                                (->> (:files data)
                                     (d/seek (fn [[_ file]] (contains? (set (:features file)) "components/v2"))))]
                            (if (and has-file-v2? (not (contains? features "components/v2")))
                              {:uri (:uri file) :error "dashboard.import.analyze-error.components-v2"}
                              (hash-map :uri (:uri file) :data data :type "application/zip"))))))
                  (->> st
                       (rx/filter (fn [data] (= "application/octet-stream" (:type data))))
                       (rx/map (fn [_]
                                 (let [file-id (uuid/next)]
                                   {:uri (:uri file)
                                    :data {:name (:name file)
                                           :file-id file-id
                                           :files {file-id {:name (:name file)}}
                                           :status :ready}
                                    :type "application/octet-stream"}))))
                  (->> st
                       (rx/filter (fn [data] (= "other" (:type data))))
                       (rx/map (fn [_]
                                 {:uri (:uri file)
                                  :error (tr "dashboard.import.analyze-error")}))))
                 (rx/catch (fn [data]
                             (let [error (or (.-message data) (tr "dashboard.import.analyze-error"))]
                               (rx/of {:uri (:uri file) :error error}))))))))))


(defmethod impl/handler :import-files
  [{:keys [project-id files features]}]

  (let [context {:project-id project-id
                 :resolve    (resolve-factory)
                 :system-features features}
        zip-files (filter #(= "application/zip" (:type %)) files)
        binary-files (filter #(= "application/octet-stream" (:type %)) files)]

    (rx/merge
     (->> (create-files context zip-files)
          (rx/merge-map
           (fn [[file data]]
             (->> (uz/load-from-url (:uri data))
                  (rx/map #(-> context (assoc :zip %) (merge data)))
                  (rx/merge-map
                   (fn [context]
                     ;; process file retrieves a stream that will emit progress notifications
                     ;; and other that will emit the files once imported
                     (let [[progress-stream file-stream] (process-file context file)]
                       (rx/merge progress-stream
                                 (->> file-stream
                                      (rx/map
                                       (fn [file]
                                         {:status :import-finish
                                          :errors (:errors file)
                                          :file-id (:file-id data)})))))))
                  (rx/catch (fn [cause]
                              (log/error :hint (ex-message cause)
                                         :file-id (:file-id data)
                                         :cause cause)
                              (rx/of {:status :import-error
                                      :file-id (:file-id data)
                                      :error (ex-message cause)
                                      :error-data (ex-data cause)})))))))

     (->> (rx/from binary-files)
          (rx/merge-map
           (fn [data]
             (->> (http/send!
                   {:uri (:uri data)
                    :response-type :blob
                    :method :get})
                  (rx/map :body)
                  (rx/mapcat (fn [file]
                               (->> (rp/cmd! ::sse/import-binfile
                                             {:name (str/replace (:name data) #".penpot$" "")
                                              :file file
                                              :project-id project-id})
                                    (rx/tap (fn [event]
                                              (let [payload (sse/get-payload event)
                                                    type    (sse/get-type event)]
                                                (if (= type "progress")
                                                  (log/dbg :hint "import-binfile: progress" :section (:section payload) :name (:name payload))
                                                  (log/dbg :hint "import-binfile: end")))))
                                    (rx/filter sse/end-of-stream?)
                                    (rx/map (fn [_]
                                              {:status :import-finish
                                               :file-id (:file-id data)})))))
                  (rx/catch (fn [cause]
                              (log/error :hint "unexpected error on import process"
                                         :project-id project-id
                                         ::log/sync? true)
                              (let [edata (if (map? cause) cause (ex-data cause))]
                                (println "Error data:")
                                (pp/pprint (dissoc edata :explain) {:level 3 :length 10})

                                (when (string? (:explain edata))
                                  (js/console.log (:explain edata)))

                                (rx/of {:status :import-error
                                        :file-id (:file-id data)})))))))))))

