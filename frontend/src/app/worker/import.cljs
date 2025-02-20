;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.worker.import
  (:refer-clojure :exclude [resolve])
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.files.builder :as fb]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as gpa]
   [app.common.json :as json]
   [app.common.logging :as log]
   [app.common.media :as cm]
   [app.common.schema :as sm]
   [app.common.text :as ct]
   [app.common.time :as tm]
   [app.common.uuid :as uuid]
   [app.main.repo :as rp]
   [app.util.http :as http]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.sse :as sse]
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

(def ^:private iso-date-rx
  "Incomplete ISO regex for detect datetime-like values on strings"
  #"^\d{4}-[01]\d-[0-3]\dT[0-2]\d:[0-5]\d:[0-5]\d.*")

(defn read-json-key
  [m]
  (or (sm/parse-uuid m)
      (json/read-kebab-key m)))

(defn read-json-val
  [m]
  (cond
    (and (string? m)
         (re-matches sm/uuid-rx m))
    (uuid/uuid m)

    (and (string? m)
         (re-matches iso-date-rx m))
    (or (ex/ignoring (tm/parse-instant m)) m)

    :else
    m))

(defn get-file
  "Resolves the file inside the context given its id and the
  data. LEGACY"
  ([context type]
   (get-file context type nil nil))

  ([context type id]
   (get-file context type id nil))

  ([context type id media]
   (let [file-id (:file-id context)
         path (case type
                :manifest           "manifest.json"
                :page               (str file-id "/" id ".svg")
                :colors-list        (str file-id "/colors.json")
                :colors             (let [ext (cm/mtype->extension (:mtype media))]
                                      (str/concat file-id "/colors/" id ext))
                :typographies       (str file-id "/typographies.json")
                :media-list         (str file-id "/media.json")
                :media              (let [ext (cm/mtype->extension (:mtype media))]
                                      (str/concat file-id "/media/" id ext))
                :components         (str file-id "/components.svg")
                :deleted-components (str file-id "/deleted-components.svg"))

         parse-svg?  (and (not= type :media) (str/ends-with? path "svg"))
         parse-json? (and (not= type :media) (str/ends-with? path "json"))
         file-type   (if (or parse-svg? parse-json?) "text" "blob")]

     (log/debug :action "parsing" :path path)

     (let [stream (->> (uz/get-file (:zip context) path file-type)
                       (rx/map :content))]

       (cond
         parse-svg?
         (rx/map tubax/xml->clj stream)

         parse-json?
         (rx/map #(json/decode % :key-fn read-json-key :val-fn read-json-val) stream)

         :else
         stream)))))

(defn- read-zip-manifest
  [zipfile]
  (->> (uz/get-file zipfile "manifest.json")
       (rx/map :content)
       (rx/map json/decode)))

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
     (let [progress {:type type
                     :file file
                     :current current
                     :total total}]
       (log/debug :status :progress :progress progress)
       (rx/push! (:progress context) {:file-id (:file-id context)
                                      :status :progress
                                      :progress progress})))))

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
              :is-shared (:is-shared context)
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

(defn slurp-uri
  ([uri] (slurp-uri uri :text))
  ([uri response-type]
   (->> (http/send!
         {:uri uri
          :response-type response-type
          :method :get})
        (rx/map :body))))

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

(defn resolve-fills-content
  [fills context]
  (let [resolve (:resolve context)]
    (->> fills
         (mapv
          (fn [fill]
            (cond-> fill
              (uuid? (get fill :fill-color-ref-id))
              (d/update-when :fill-color-ref-id resolve)

              (uuid? (get fill :fill-color-ref-file))
              (d/update-when :fill-color-ref-file resolve)))))))

(defn resolve-strokes-content
  [fills context]
  (let [resolve (:resolve context)]
    (->> fills
         (mapv
          (fn [fill]
            (cond-> fill
              (uuid? (get fill :stroke-color-ref-id))
              (d/update-when :stroke-color-ref-id resolve)

              (uuid? (get fill :stroke-color-ref-file))
              (d/update-when :stroke-color-ref-file resolve)))))))

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

        (cond-> (:fills data)
          (d/update-when :fills resolve-fills-content context))

        (cond-> (:strokes data)
          (d/update-when :strokes resolve-strokes-content context))

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
                               (translate-frame type file))
                             ;; Shapes inside the deleted component should be stored with absolute coordinates
                             ;; so we calculate that with the x and y stored in the context
                             (cond-> (:x context)
                               (assoc :x (:x context)))
                             (cond-> (:y context)
                               (assoc :y (:y context))))]
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
  (let [nodes     (parser/node-seq content)
        file-id   (:id file)
        resolve   (:resolve context)
        page-data (-> (parser/parse-page-data content)
                      (assoc :name page-name)
                      (assoc :id (resolve page-id)))

        flows     (->> (get page-data :flows)
                       (map #(update % :starting-frame resolve))
                       (d/index-by :id)
                       (not-empty))

        guides    (-> (get page-data :guides)
                      (update-vals #(update % :frame-id resolve))
                      (not-empty))

        page-data (cond-> page-data
                    flows
                    (assoc :flows flows)

                    guides
                    (assoc :guides guides))

        file      (fb/add-page file page-data)

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
  (let [resolve              (:resolve context)
        content              (parser/find-node node :g)
        file-id              (:id file)
        old-id               (parser/get-id node)
        id                   (resolve old-id)
        path                 (get-in node [:attrs :penpot:path] "")
        main-instance-id     (resolve (uuid (get-in node [:attrs :penpot:main-instance-id] "")))
        main-instance-page   (resolve (uuid (get-in node [:attrs :penpot:main-instance-page] "")))
        main-instance-x      (-> (get-in node [:attrs :penpot:main-instance-x] "") (d/parse-double))
        main-instance-y      (-> (get-in node [:attrs :penpot:main-instance-y] "") (d/parse-double))
        main-instance-parent (resolve (uuid (get-in node [:attrs :penpot:main-instance-parent] "")))
        main-instance-frame  (resolve (uuid (get-in node [:attrs :penpot:main-instance-frame] "")))
        type                 (parser/get-type content)

        data (-> (parser/parse-data type content)
                 (assoc :path path)
                 (assoc :id id)
                 (assoc :main-instance-id main-instance-id)
                 (assoc :main-instance-page main-instance-page)
                 (assoc :main-instance-x main-instance-x)
                 (assoc :main-instance-y main-instance-y)
                 (assoc :main-instance-parent main-instance-parent)
                 (assoc :main-instance-frame main-instance-frame))

        file         (-> file
                         (fb/start-component data)
                         (fb/start-deleted-component data))
        component-id (:current-component-id file)
        children     (parser/node-seq node)

        ;; Shapes inside the deleted component should be stored with absolute coordinates so we include this info in the context.
        context (-> context
                    (assoc :x main-instance-x)
                    (assoc :y main-instance-y))]
    (->> (rx/from children)
         (rx/filter parser/shape?)
         (rx/skip 1)
         (rx/skip-last 1)
         (rx/mapcat (partial resolve-media context file-id))
         (rx/reduce (partial process-import-node context) file)
         (rx/map fb/finish-component)
         (rx/map (partial fb/finish-deleted-component component-id)))))

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
          (fn [file color]
            (let [color (-> color
                            (d/update-in-when [:gradient :type] keyword)
                            (d/update-in-when [:image :id] resolve)
                            (update :id resolve))]
              (fb/add-library-color file color)))]
      (->> (get-file context :colors-list)
           (rx/merge-map identity)
           (rx/mapcat
            (fn [[id color]]
              (let [color (assoc color :id id)
                    color-image (:image color)
                    upload-image? (some? color-image)
                    color-image-id (:id color-image)]
                (if upload-image?
                  (->> (get-file context :colors color-image-id color-image)
                       (rx/map (fn [blob]
                                 (let [content (.slice blob 0 (.-size blob) (:mtype color-image))]
                                   {:name (:name color-image)
                                    :id (resolve color-image-id)
                                    :file-id (:id file)
                                    :content content
                                    :is-local false})))
                       (rx/tap #(progress! context :upload-media (:name %)))
                       (rx/merge-map #(rp/cmd! :upload-file-media-object %))
                       (rx/map (constantly color))
                       (rx/catch #(do (.error js/console (str "Error uploading color-image: " (:name color-image)))
                                      (rx/empty))))
                  (rx/of color)))))
           (rx/reduce add-color file)))
    (rx/of file)))

(defn process-library-typographies
  [context file]
  (if (:has-typographies context)
    (let [resolve (:resolve context)]
      (->> (get-file context :typographies)
           (rx/merge-map identity)
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
           (rx/merge-map identity)
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

(defn- analyze-file-legacy-zip-entry
  [features entry]
  ;; NOTE: LEGACY manifest reading mechanism, we can't
  ;; reuse the new read-zip-manifest funcion here
  (->> (rx/from (uz/load (:body entry)))
       (rx/merge-map #(get-file {:zip %} :manifest))
       (rx/mapcat
        (fn [manifest]
          ;; Checks if the file is exported with
          ;; components v2 and the current team
          ;; only supports components v1
          (let [has-file-v2?
                (->> (:files manifest)
                     (d/seek (fn [[_ file]] (contains? (set (:features file)) "components/v2"))))]

            (if (and has-file-v2? (not (contains? features "components/v2")))
              (rx/of (-> entry
                         (assoc :error "dashboard.import.analyze-error.components-v2")
                         (dissoc :body)))
              (->> (rx/from (:files manifest))
                   (rx/map (fn [[file-id data]]
                             (-> entry
                                 (dissoc :body)
                                 (merge data)
                                 (dissoc :shared)
                                 (assoc :is-shared (:shared data))
                                 (assoc :file-id file-id)
                                 (assoc :status :success)))))))))))

;; NOTE: this is a limited subset schema for the manifest file of
;; binfile-v3 format; is used for partially parse it and read the
;; files referenced inside the exported file

(def ^:private schema:manifest
  [:map {:title "Manifest"}
   [:type :string]
   [:files
    [:vector
     [:map
      [:id ::sm/uuid]
      [:name :string]]]]])

(def ^:private decode-manifest
  (sm/decoder schema:manifest sm/json-transformer))

(defn analyze-file
  [features {:keys [uri] :as file}]
  (let [stream (->> (slurp-uri uri :buffer)
                    (rx/merge-map
                     (fn [body]
                       (let [mtype (parse-mtype body)]
                         (cond
                           (= "application/zip" mtype)
                           (->> (uz/load body)
                                (rx/merge-map read-zip-manifest)
                                (rx/map
                                 (fn [manifest]
                                   (if (= (:type manifest) "penpot/export-files")
                                     (let [manifest (decode-manifest manifest)]
                                       (assoc file :type :binfile-v3 :files (:files manifest)))
                                     (assoc file :type :legacy-zip :body body)))))

                           (= "application/octet-stream" mtype)
                           (rx/of (assoc file :type :binfile-v1))

                           :else
                           (rx/of (assoc file :type :unknown))))))

                    (rx/share))]

    (->> (rx/merge
          (->> stream
               (rx/filter (fn [entry] (= :legacy-zip (:type entry))))
               (rx/merge-map (partial analyze-file-legacy-zip-entry features)))

          (->> stream
               (rx/filter (fn [entry] (= :binfile-v1 (:type entry))))
               (rx/map (fn [entry]
                         (let [file-id (uuid/next)]
                           (-> entry
                               (assoc :file-id file-id)
                               (assoc :name (:name file))
                               (assoc :status :success))))))

          (->> stream
               (rx/filter (fn [entry] (= :binfile-v3 (:type entry))))
               (rx/merge-map (fn [{:keys [files] :as entry}]
                               (->> (rx/from files)
                                    (rx/map (fn [file]
                                              (-> entry
                                                  (dissoc :files)
                                                  (assoc :name (:name file))
                                                  (assoc :file-id (:id file))
                                                  (assoc :status :success))))))))

          (->> stream
               (rx/filter (fn [data] (= :unknown (:type data))))
               (rx/map (fn [_]
                         {:uri (:uri file)
                          :status :error
                          :error (tr "dashboard.import.analyze-error")}))))

         (rx/catch (fn [cause]
                     (let [error (or (ex-message cause) (tr "dashboard.import.analyze-error"))]
                       (rx/of (assoc file :error error :status :error))))))))

(defmethod impl/handler :analyze-import
  [{:keys [files features]}]
  (->> (rx/from files)
       (rx/merge-map (partial analyze-file features))))

(defmethod impl/handler :import-files
  [{:keys [project-id files features]}]
  (let [context    {:project-id project-id
                    :resolve    (resolve-factory)
                    :system-features features}

        legacy-zip (filter #(= :legacy-zip (:type %)) files)
        binfile-v1 (filter #(= :binfile-v1 (:type %)) files)
        binfile-v3 (filter #(= :binfile-v3 (:type %)) files)]

    (rx/merge

     ;; NOTE: LEGACY, will be removed so no new development should be
     ;; done for this part
     (->> (create-files context legacy-zip)
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
                                         (if-let [errors (not-empty (:errors file))]
                                           {:status :error
                                            :error (first errors)
                                            :file-id (:file-id data)}
                                           {:status :finish
                                            :file-id (:file-id data)}))))))))
                  (rx/catch (fn [cause]
                              (let [data (ex-data cause)]
                                (log/error :hint (ex-message cause)
                                           :file-id (:file-id data))
                                (when-let [explain (:explain data)]
                                  (js/console.log explain)))

                              (rx/of {:status :error
                                      :file-id (:file-id data)
                                      :error (ex-message cause)})))))))

     (->> (rx/from binfile-v1)
          (rx/merge-map
           (fn [data]
             (->> (http/send!
                   {:uri (:uri data)
                    :response-type :blob
                    :method :get})
                  (rx/map :body)
                  (rx/mapcat
                   (fn [file]
                     (->> (rp/cmd! ::sse/import-binfile
                                   {:name (str/replace (:name data) #".penpot$" "")
                                    :file file
                                    :project-id project-id})
                          (rx/tap (fn [event]
                                    (let [payload (sse/get-payload event)
                                          type    (sse/get-type event)]
                                      (if (= type "progress")
                                        (log/dbg :hint "import-binfile: progress"
                                                 :section (:section payload)
                                                 :name (:name payload))
                                        (log/dbg :hint "import-binfile: end")))))
                          (rx/filter sse/end-of-stream?)
                          (rx/map (fn [_]
                                    {:status :finish
                                     :file-id (:file-id data)})))))

                  (rx/catch
                   (fn [cause]
                     (log/error :hint "unexpected error on import process"
                                :project-id project-id
                                :cause cause)
                     (rx/of {:status :error
                             :error (ex-message cause)
                             :file-id (:file-id data)})))))))

     (->> (rx/from binfile-v3)
          (rx/reduce (fn [result file]
                       (update result (:uri file) (fnil conj []) file))
                     {})
          (rx/mapcat identity)
          (rx/merge-map
           (fn [[uri entries]]
             (->> (slurp-uri uri :blob)
                  (rx/mapcat (fn [content]
                               ;; FIXME: implement the naming and filtering
                               (->> (rp/cmd! ::sse/import-binfile
                                             {:name (-> entries first :name)
                                              :file content
                                              :version 3
                                              :project-id project-id})
                                    (rx/tap (fn [event]
                                              (let [payload (sse/get-payload event)
                                                    type    (sse/get-type event)]
                                                (if (= type "progress")
                                                  (log/dbg :hint "import-binfile: progress"
                                                           :section (:section payload)
                                                           :name (:name payload))
                                                  (log/dbg :hint "import-binfile: end")))))
                                    (rx/filter sse/end-of-stream?)
                                    (rx/mapcat (fn [_]
                                                 (->> (rx/from entries)
                                                      (rx/map (fn [entry]
                                                                {:status :finish
                                                                 :file-id (:file-id entry)}))))))))

                  (rx/catch
                   (fn [cause]
                     (log/error :hint "unexpected error on import process"
                                :project-id project-id
                                ::log/sync? true
                                :cause cause)
                     (->> (rx/from entries)
                          (rx/map (fn [entry]
                                    {:status :error
                                     :error (ex-message cause)
                                     :file-id (:file-id entry)}))))))))))))


