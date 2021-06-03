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
   [app.util.http :as http]
   [app.util.import.parser :as cip]
   [app.util.zip :as uz]
   [app.worker.impl :as impl]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [tubax.core :as tubax]))

;; Upload changes batches size
(def change-batch-size 100)

(defn create-file
  "Create a new file on the back-end"
  [project-id name]
  (let [file-id (uuid/next)]
    (rp/mutation
     :create-file
     {:id file-id
      :name name
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

    (->> (rx/from changes-batches)
         (rx/merge-map
          (fn [cur-changes-batch]
            (rp/mutation
             :update-file
             {:id file-id
              :session-id session-id
              :revn @revn
              :changes cur-changes-batch})))

         (rx/tap #(reset! revn (:revn %))))))

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

(defn parse-file-name
  [dir]
  (if (str/ends-with? dir "/")
    (subs dir 0 (dec (count dir)))
    dir))

(defn parse-page-name
  [path]
  (let [[file page] (str/split path "/")]
    (str/replace page ".svg" "")))

(defn add-shape-file
  [file node]

  (let [type   (cip/get-type node)
        close? (cip/close? node)
        data   (cip/parse-data type node)]

    (if close?
      (case type
        :frame
        (fb/close-artboard file)

        :group
        (fb/close-group file)

        ;; default
        file)

      (case type
        :frame    (fb/add-artboard file data)
        :group    (fb/add-group file data)
        :rect     (fb/create-rect file data)
        :circle   (fb/create-circle file data)
        :path     (fb/create-path file data)
        :text     (fb/create-text file data)
        :image    (fb/create-image file data)

        ;; default
        file))))

(defn merge-reduce [f seed ob]
  (->> (rx/concat
        (rx/of seed)
        (rx/merge-scan f seed ob))
       (rx/last)))

(defn resolve-images
  [file-id node]
  (if (and (cip/shape? node) (= (cip/get-type node) :image) (not (cip/close? node)))
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
    (rx/of node)))

(defn import-page
  [file {:keys [path content]}]
  (let [page-name (parse-page-name path)]
    (if (cip/valid? content)
      (let [nodes (->> content cip/node-seq)
            file-id (:id file)]
        (->> (rx/from nodes)
             (rx/filter cip/shape?)
             (rx/mapcat (partial resolve-images file-id))
             (rx/reduce add-shape-file (fb/add-page file page-name))
             (rx/map fb/close-page)))
      (rx/empty))))

(defmethod impl/handler :import-file
  [{:keys [project-id files]}]

  (let [extract-stream
        (->> (rx/from files)
             (rx/merge-map uz/extract-files))

        dir-str
        (->> extract-stream
             (rx/filter #(contains? % :dir))
             (rx/map :dir))

        file-str
        (->> extract-stream
             (rx/filter #(not (contains? % :dir)))
             (rx/map #(d/update-when % :content tubax/xml->clj)))]

    (->> dir-str
         (rx/merge-map #(create-file project-id (parse-file-name %)))
         (rx/merge-map
          (fn [file]
            (rx/concat
             (->> file-str
                  (rx/filter #(str/starts-with? (:path %) (:name file)))
                  (merge-reduce import-page file)
                  (rx/flat-map send-changes)
                  (rx/catch (fn [err]
                              (.error js/console "ERROR" err (clj->js (.-data err)))

                              ;; We delete the file when there is an error
                              (rp/mutation! :delete-file {:id (:id file)})))
                  (rx/ignore))

             (rx/of (select-keys file [:id :name]))))))))
