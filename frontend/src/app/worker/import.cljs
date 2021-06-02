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
   [app.util.import.parser :as cip]
   [app.util.zip :as uz]
   [app.worker.impl :as impl]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [tubax.core :as tubax]))

;; Upload changes batches size
(def change-batch-size 100)

(defn create-empty-file
  "Create a new file on the back-end"
  [project-id file]
  (rp/mutation
   :create-file
   {:id (:id file)
    :name (:name file)
    :project-id project-id
    :data (-> cp/empty-file-data
              (assoc :id (:id file)))}))

(defn send-changes
  "Creates batches of changes to be sent to the backend"
  [file init-revn]
  (let [revn (atom init-revn)
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

(defn persist-file
  "Sends to the back-end the imported data"
  [project-id file]
  (->> (create-empty-file project-id file)
       (rx/flat-map #(send-changes file (:revn %)))))

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

(defn import-page
  [file {:keys [path content]}]
  (let [page-name (parse-page-name path)]
    (when (cip/valid? content)
      (let [nodes (->> content cip/node-seq)]
        (->> nodes
             (filter cip/shape?)
             (reduce add-shape-file (fb/add-page file page-name))
             (fb/close-page))))))

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
         (rx/merge-map
          (fn [dir]
            (let [file (fb/create-file (parse-file-name dir))]
              (rx/concat
               (->> file-str
                    (rx/filter #(str/starts-with? (:path %) dir))
                    (rx/reduce import-page file)
                    (rx/flat-map #(persist-file project-id %))
                    (rx/ignore))

               (rx/of (select-keys file [:id :name])))))))))
