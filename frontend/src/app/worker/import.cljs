;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.worker.import
  (:require
   [app.common.data :as d]
   [app.common.file-builder :as fb]
   [app.common.pages :as cp]
   [app.common.uuid :as uuid]
   [app.main.repo :as rp]
   [app.util.dom :as dom]
   [app.util.http :as http]
   [app.util.import.parser :as cip]
   [app.util.json :as json]
   [app.util.zip :as uz]
   [app.worker.impl :as impl]
   [beicon.core :as rx]
   [tubax.core :as tubax]))

;; Upload changes batches size
(def change-batch-size 100)

(defn create-file
  "Create a new file on the back-end"
  [project-id file-desc]
  (let [file-id (uuid/next)]
    (rp/mutation
     :create-temp-file
     {:id file-id
      :name (:name file-desc)
      :is-shared (:shared file-desc)
      :project-id project-id
      :data (-> cp/empty-file-data (assoc :id file-id))})))

(defn send-changes
  "Creates batches of changes to be sent to the backend"
  [file]
  (let [revn (atom (:revn file))
        file-id (:id file)
        session-id (uuid/next)
        changes-batches
        (->> (fb/generate-changes file)
             (partition change-batch-size change-batch-size nil)
             (mapv vec))]

    (rx/concat
     (->> (rx/from changes-batches)
          (rx/mapcat
           #(rp/mutation
             :update-file
             {:id file-id
              :session-id session-id
              :revn @revn
              :changes %}))
          (rx/map first)
          (rx/tap #(reset! revn (:revn %))))

     (rp/mutation :persist-temp-file {:id (:id file)}))))

(defn upload-media-files
  "Upload a image to the backend and returns its id"
  [file-id name data-uri]
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
       (rx/flat-map #(rp/mutation! :upload-file-media-object %))))

(defn add-shape-file
  [file node]

  (let [type         (cip/get-type node)
        close?       (cip/close? node)]
    (if close?
      (case type
        :frame    (fb/close-artboard file)
        :group    (fb/close-group file)
        :svg-raw  (fb/close-svg-raw file)
        #_default file)

      (let [data         (cip/parse-data type node)
            old-id       (cip/get-id node)
            interactions (cip/parse-interactions node)

            file (case type
                   :frame    (fb/add-artboard file data)
                   :group    (fb/add-group file data)
                   :rect     (fb/create-rect file data)
                   :circle   (fb/create-circle file data)
                   :path     (fb/create-path file data)
                   :text     (fb/create-text file data)
                   :image    (fb/create-image file data)
                   :svg-raw  (fb/create-svg-raw file data)
                   #_default file)]

        (assert (some? old-id) "ID not found")

        ;; We store this data for post-processing after every shape has been
        ;; added
        (cond-> file
          (some? (:last-id file))
          (assoc-in [:id-mapping old-id] (:last-id file))

          (d/not-empty? interactions)
          (assoc-in [:interactions old-id] interactions))))))

(defn post-process-file
  [file]

  (letfn [(add-interaction
            [id file {:keys [action-type event-type destination] :as interaction}]
            (fb/add-interaction file action-type event-type id destination))

          (add-interactions
            [file [old-id interactions]]
            (let [id (get-in file [:id-mapping old-id])]
              (->> interactions
                   (mapv (fn [interaction]
                           (let [id (get-in file [:id-mapping (:destination interaction)])]
                             (assoc interaction :destination id))))
                   (reduce
                    (partial add-interaction id) file))))

          (process-interactions
            [file]
            (reduce add-interactions file (:interactions file)))]

    (-> file
        (process-interactions)
        (dissoc :id-mapping :interactions))))

(defn merge-reduce [f seed ob]
  (let [current-acc (atom seed)]
    (->> (rx/concat
          (rx/of seed)
          (->> ob
               (rx/mapcat #(f @current-acc %))
               (rx/tap #(reset! current-acc %))))
         (rx/last))))

(defn skip-last
  [n ob]
  (.pipe ob (.skipLast js/rxjsOperators (int n))))

(defn resolve-media
  [file-id node]
  (if (and (not (cip/close? node))
           (cip/has-image? node))
    (let [name     (cip/get-image-name node)
          data-uri (cip/get-image-data node)]
      (->> (upload-media-files file-id name data-uri)
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
  [file [page-name content]]
  (if (cip/valid? content)
    (let [nodes (->> content cip/node-seq)
          file-id (:id file)
          page-data (-> (cip/parse-page-data content)
                        (assoc :name page-name))]
      (->> (rx/from nodes)
           (rx/filter cip/shape?)
           (rx/mapcat (partial resolve-media file-id))
           (rx/reduce add-shape-file (fb/add-page file page-data))
           (rx/map post-process-file)
           (rx/map fb/close-page)))
    (rx/empty)))

(defn get-page-path [dir-id id]
  (str dir-id "/" id ".svg"))

(defn process-page [file-id zip [page-id page-name]]
  (let [path (get-page-path (d/name file-id) page-id)]
    (->> (uz/get-file zip path)
         (rx/map (comp tubax/xml->clj :content))
         (rx/map #(vector page-name %)))))

(defn process-file-pages
  [file file-id file-desc zip]
  (let [index (:pages-index file-desc)
        pages (->> (:pages file-desc)
                   (mapv #(vector % (get-in index [(keyword %) :name]))))]
    (->> (rx/from pages)
         (rx/mapcat #(process-page file-id zip %))
         (merge-reduce import-page file))))

(defn process-library-colors
  [file file-id file-desc zip]
  (if (:has-colors file-desc)
    (let [add-color
          (fn [file [id color]]
            (let [color (-> (d/kebab-keys color)
                            (d/update-in-when [:gradient :type] keyword))
                  file (fb/add-library-color file color)]
              (assoc file [:library-mapping id] (:last-id file))))

          path (str (d/name file-id) "/colors.json")]
      (->> (uz/get-file zip path)
           (rx/mapcat (comp json/decode :content))
           (rx/reduce add-color file)))

    (rx/of file)))

(defn process-library-typographies
  [file file-id file-desc zip]
  (if (:has-typographies file-desc)
    (let [add-typography
          (fn [file [id typography]]
            (let [typography (d/kebab-keys typography)
                  file (fb/add-library-typography file typography)]
              (assoc file [:library-mapping id] (:last-id file))))

          path (str (d/name file-id) "/typographies.json")]
      (->> (uz/get-file zip path)
           (rx/mapcat (comp json/decode :content))
           (rx/reduce add-typography file)))

    (rx/of file)))

(defn process-library-media
  [file file-id file-desc zip]
  (if (:has-media file-desc)
    (let [add-media
          (fn [file media]
            (let [file (fb/add-library-media file (dissoc media :old-id))]
              (assoc file [:library-mapping (:old-id media)] (:last-id file))))

          path (str (d/name file-id) "/media.json")]

      (->> (uz/get-file zip path)
           (rx/mapcat (comp json/decode :content))
           (rx/flat-map
            (fn [[id media]]
              (let [file-path (str (d/name file-id) "/media/" (d/name id) "." (dom/mtype->extension (:mtype media)))]
                (->> (uz/get-file zip file-path "blob")
                     (rx/map (fn [{blob :content}]
                               (let [content (.slice blob 0 (.-size blob) (:mtype media))]
                                 {:name (:name media)
                                  :file-id (:id file)
                                  :content content
                                  :is-local false})))
                     (rx/flat-map #(rp/mutation! :upload-file-media-object %))
                     (rx/map (fn [response]
                               (-> media
                                   (assoc :old-id id)
                                   (assoc :id (:id response)))))))))
           (rx/reduce add-media file)))

    (rx/of file)))

(defn add-component [file content]
  (let [content      (cip/find-node content :g)
        data         (cip/parse-data :group content)
        file         (-> file (fb/start-component data))
        id           (-> (get-in content [:attrs :id]) (uuid/uuid))
        component-id (:last-id file)
        file         (assoc file [:library-mapping id] component-id)
        nodes        (cip/node-seq content)]

    (->> (rx/from nodes)
         (rx/filter cip/shape?)
         (rx/skip 1)
         (skip-last 1)
         (rx/mapcat (partial resolve-media (:id file)))
         (rx/reduce add-shape-file file)
         (rx/map #(fb/finish-component %)))))

(defn process-library-components
  [file file-id file-desc zip]
  (if (:has-components file-desc)
    (let [path (str (d/name file-id) "/components.svg")]
      (->> (uz/get-file zip path)
           (rx/map (comp tubax/xml->clj :content))
           (rx/flat-map (fn [content] (->> (cip/node-seq content) (filter #(= :symbol (:tag %))))))
           (merge-reduce add-component file)))
    (rx/of file)))

(defn process-file
  [file file-id file-desc zip]
  (->> (process-file-pages file file-id file-desc zip)
       (rx/flat-map #(process-library-colors % file-id file-desc zip))
       (rx/flat-map #(process-library-typographies % file-id file-desc zip))
       (rx/flat-map #(process-library-media % file-id file-desc zip))
       (rx/flat-map #(process-library-components % file-id file-desc zip))
       (rx/flat-map send-changes)
       (rx/ignore)))

(defn process-package
  [project-id zip-file]
  (->> (uz/get-file zip-file "manifest.json")
       (rx/flat-map (comp :files json/decode :content))
       (rx/flat-map
        (fn [[file-id file-desc]]
          (let [file-desc (d/kebab-keys file-desc)]
            (->> (create-file project-id file-desc)
                 (rx/flat-map #(process-file % file-id file-desc zip-file))))))))

(defmethod impl/handler :import-file
  [{:keys [project-id files]}]

  (->> (rx/from files)
       (rx/flat-map uz/load-from-url)
       (rx/flat-map (partial process-package project-id))
       (rx/catch
           (fn [err]
             (.error js/console "ERROR" err (clj->js (.-data err)))))))
