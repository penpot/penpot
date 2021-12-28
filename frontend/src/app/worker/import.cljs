;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.worker.import
  (:refer-clojure :exclude [resolve])
  (:require
   [app.common.data :as d]
   [app.common.file-builder :as fb]
   [app.common.geom.point :as gpt]
   [app.common.geom.shapes.path :as gpa]
   [app.common.logging :as log]
   [app.common.pages :as cp]
   [app.common.text :as ct]
   [app.common.uuid :as uuid]
   [app.main.repo :as rp]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.import.parser :as cip]
   [app.util.json :as json]
   [app.util.zip :as uz]
   [app.worker.impl :as impl]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [tubax.core :as tubax]))

(log/set-level! :warn)

;; Upload changes batches size
(def ^:const change-batch-size 100)

(defn get-file
  "Resolves the file inside the context given its id and the data"
  ([context type]
   (get-file context type nil nil))

  ([context type id]
   (get-file context type id nil))

  ([context type id media]
   (let [file-id (:file-id context)
         path (case type
                :manifest     (str "manifest.json")
                :page         (str file-id "/" id ".svg")
                :colors       (str file-id "/colors.json")
                :typographies (str file-id "/typographies.json")
                :media-list   (str file-id "/media.json")
                :media        (let [ext (dom/mtype->extension (:mtype media))]
                                (str file-id "/media/" id "." ext))
                :components   (str file-id "/components.svg"))

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
   (keyword? type)
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
  [context]
  (let [resolve (:resolve context)
        file-id (resolve (:file-id context))]
    (rp/mutation
     :create-temp-file
     {:id file-id
      :name (:name context)
      :is-shared (:shared context)
      :project-id (:project-id context)
      :data (-> cp/empty-file-data (assoc :id file-id))})))

(defn link-file-libraries
  "Create a new file on the back-end"
  [context]
  (let [resolve (:resolve context)
        file-id (resolve (:file-id context))
        libraries (->> context :libraries (mapv resolve))]
    (->> (rx/from libraries)
         (rx/map #(hash-map :file-id file-id :library-id %))
         (rx/flat-map (partial rp/mutation :link-file-to-library)))))

(defn persist-file [file]
  (rp/mutation :persist-temp-file {:id (:id file)}))

(defn send-changes
  "Creates batches of changes to be sent to the backend"
  [context file]
  (let [revn (atom (:revn file))
        file-id (:id file)
        session-id (uuid/next)
        changes-batches
        (->> (fb/generate-changes file)
             (partition change-batch-size change-batch-size nil)
             (mapv vec))

        current (atom 0)
        total (count changes-batches)]

    (rx/concat
     (->> (rx/from changes-batches)
          (rx/mapcat
           (fn [change-batch]
             (->> (rp/mutation :update-file
                               {:id file-id
                                :session-id session-id
                                :revn @revn
                                :changes change-batch})
                  (rx/tap #(do (swap! current inc)
                               (progress! context
                                          :upload-data @current total))))))

          (rx/map first)
          (rx/tap #(reset! revn (:revn %)))
          (rx/ignore))

     (rp/mutation :persist-temp-file {:id file-id}))))

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
       (rx/flat-map #(rp/mutation! :upload-file-media-object %))))

(defn resolve-text-content [node context]
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
          (d/update-when :content resolve-text-content context)))))

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

  (let [type         (cip/get-type node)
        close?       (cip/close? node)]
    (if close?
      (case type
        :frame    (fb/close-artboard file)
        :group    (fb/close-group file)
        :bool     (fb/close-bool file)
        :svg-raw  (fb/close-svg-raw file)
        #_default file)

      (let [resolve      (:resolve context)
            old-id       (cip/get-id node)
            interactions (->> (cip/parse-interactions node)
                              (mapv #(update % :destination resolve)))

            data         (-> (cip/parse-data type node)
                             (resolve-data-ids type context)
                             (cond-> (some? old-id)
                               (assoc :id (resolve old-id)))
                             (cond-> (< (:version context 1) 2)
                               (translate-frame type file)))

            file (case type
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
          (assoc-in [:interactions (:id data)] interactions))))))

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
  (if (and (not (cip/close? node))
           (cip/has-image? node))
    (let [name     (cip/get-image-name node)
          data-uri (cip/get-image-data node)]
      (->> (upload-media-files context file-id name data-uri)
           (rx/catch #(do (.error js/console "Error uploading media: " name)
                          (rx/of node)))
           (rx/map
            (fn [media]
              (-> node
                  (assoc-in [:attrs :penpot:media-id]     (:id media))
                  (assoc-in [:attrs :penpot:media-width]  (:width media))
                  (assoc-in [:attrs :penpot:media-height] (:height media))
                  (assoc-in [:attrs :penpot:media-mtype]  (:mtype media)))))))

    ;; If the node is not an image just return the node
    (->> (rx/of node)
         (rx/observe-on :async))))

(defn import-page
  [context file [page-id page-name content]]
  (let [nodes (->> content cip/node-seq)
        file-id (:id file)
        resolve (:resolve context)
        page-data (-> (cip/parse-page-data content)
                      (assoc :name page-name)
                      (assoc :id (resolve page-id)))
        flows     (->> (get-in page-data [:options :flows])
                       (mapv #(update % :starting-frame resolve)))
        page-data (d/assoc-in-when page-data [:options :flows] flows)
        file      (-> file (fb/add-page page-data))]
    (->> (rx/from nodes)
         (rx/filter cip/shape?)
         (rx/mapcat (partial resolve-media context file-id))
         (rx/reduce (partial process-import-node context) file)
         (rx/map (comp fb/close-page setup-interactions)))))

(defn import-component [context file node]
  (let [resolve      (:resolve context)
        content      (cip/find-node node :g)
        file-id      (:id file)
        old-id       (cip/get-id node)
        id           (resolve old-id)
        path         (get-in node [:attrs :penpot:path] "")
        data         (-> (cip/parse-data :group content)
                         (assoc :path path)
                         (assoc :id id))

        file         (-> file (fb/start-component data))
        children      (cip/node-seq node)]

    (->> (rx/from children)
         (rx/filter cip/shape?)
         (rx/skip 1)
         (rx/skip-last 1)
         (rx/mapcat (partial resolve-media context file-id))
         (rx/reduce (partial process-import-node context) file)
         (rx/map fb/finish-component))))

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
           (rx/flat-map (comp d/kebab-keys cip/string->uuid))
           (rx/reduce add-color file)))

    (rx/of file)))

(defn process-library-typographies
  [context file]
  (if (:has-typographies context)
    (let [resolve (:resolve context)]
      (->> (get-file context :typographies)
           (rx/flat-map (comp d/kebab-keys cip/string->uuid))
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
           (rx/flat-map (comp d/kebab-keys cip/string->uuid))
           (rx/mapcat
            (fn [[id media]]
              (let [media (assoc media :id (resolve id))]
                (->> (get-file context :media id media)
                     (rx/map (fn [blob]
                               (let [content (.slice blob 0 (.-size blob) (:mtype media))]
                                 {:name (:name media)
                                  :id (:id media)
                                  :file-id (:id file)
                                  :content content
                                  :is-local false})))
                     (rx/tap #(progress! context :upload-media (:name %)))
                     (rx/flat-map #(rp/mutation! :upload-file-media-object %))
                     (rx/map (constantly media))
                     (rx/catch #(do (.error js/console (str "Error uploading media: " (:name media)) )
                                    (rx/empty)))))))
           (rx/reduce fb/add-library-media file)))

    (rx/of file)))

(defn process-library-components
  [context file]
  (if (:has-components context)
    (let [split-components
          (fn [content] (->> (cip/node-seq content)
                             (filter #(= :symbol (:tag %)))))]

      (->> (get-file context :components)
           (rx/flat-map split-components)
           (rx/concat-reduce (partial import-component context) file)))
    (rx/of file)))

(defn process-file
  [context file]

  (let [progress-str (rx/subject)
        context (assoc context :progress progress-str)]
    (rx/merge
     progress-str
     (->> (rx/of file)
          (rx/flat-map (partial process-pages context))
          (rx/tap #(progress! context :process-colors))
          (rx/flat-map (partial process-library-colors context))
          (rx/tap #(progress! context :process-typographies))
          (rx/flat-map (partial process-library-typographies context))
          (rx/tap #(progress! context :process-media))
          (rx/flat-map (partial process-library-media context))
          (rx/tap #(progress! context :process-components))
          (rx/flat-map (partial process-library-components context))
          (rx/flat-map (partial send-changes context))
          (rx/tap #(rx/end! progress-str))))))

(defn create-files
  [context files]

  (let [data (group-by :file-id files)]
    (rx/concat
     (->> (rx/from files)
          (rx/map #(merge context %))
          (rx/flat-map
           (fn [context]
             (->> (create-file context)
                  (rx/tap #(.log js/console "create-file" (clj->js %)))
                  (rx/map #(vector % (first (get data (:file-id context)))))))))

     (->> (rx/from files)
          (rx/map #(merge context %))
          (rx/flat-map link-file-libraries)
          (rx/ignore)))))

(defmethod impl/handler :analyze-import
  [{:keys [files]}]

  (->> (rx/from files)
       (rx/flat-map
        (fn [uri]
          (->> (rx/of uri)
               (rx/flat-map uz/load-from-url)
               (rx/flat-map #(get-file {:zip %} :manifest))
               (rx/map (comp d/kebab-keys cip/string->uuid))
               (rx/map #(hash-map :uri uri :data %))
               (rx/catch #(rx/of {:uri uri :error (.-message %)})))))))

(defmethod impl/handler :import-files
  [{:keys [project-id files]}]

  (let [context {:project-id project-id
                 :resolve    (resolve-factory)}]
    (->> (create-files context files)
         (rx/catch #(.error js/console "IMPORT ERROR" %))
         (rx/flat-map
          (fn [[file data]]
            (->> (rx/concat
                  (->> (uz/load-from-url (:uri data))
                       (rx/map #(-> context (assoc :zip %) (merge data)))
                       (rx/flat-map #(process-file % file)))
                  (rx/of
                   {:status :import-success
                    :file-id (:file-id data)}))

                 (rx/catch
                     (fn [err]
                       (.error js/console "ERROR" (:file-id data) err)
                       (rx/of {:status :import-error
                               :file-id (:file-id data)
                               :error (.-message err)
                               :error-data (clj->js (.-data err))})))))))))
