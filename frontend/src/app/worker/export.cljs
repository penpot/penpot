;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.worker.export
  (:require
   [app.main.render :as r]
   [app.main.repo :as rp]
   [app.util.dom :as dom]
   [app.util.zip :as uz]
   [app.worker.impl :as impl]
   [beicon.core :as rx]))

(defn get-page-data
  [{file-name :file-name {:keys [id name] :as data} :data}]
  (->> (r/render-page data)
       (rx/map (fn [markup]
                 {:id id
                  :name name
                  :file-name file-name
                  :markup markup}))))

(defn process-pages [file]
  (let [pages (get-in file [:data :pages])
        pages-index (get-in file [:data :pages-index])]
    (->> pages
         (map #(hash-map
                :file-name (:name file)
                :data (get pages-index %))))))

(defn collect-page
  [coll {:keys [id file-name name markup] :as page}]
  (conj coll [(str file-name "/" name ".svg") markup]))

(defmethod impl/handler :export-file
  [{:keys [team-id files] :as message}]

  (let [render-stream
        (->> (rx/from (->> files (mapv :id)))
             (rx/merge-map #(rp/query :file {:id %}))
             (rx/flat-map process-pages)
             (rx/observe-on :async)
             (rx/flat-map get-page-data)
             (rx/share))]

    (rx/merge
     (->> render-stream
          (rx/map #(hash-map :type :progress
                             :data (str "Render " (:file-name %) " - " (:name %)))))
     (->> render-stream
          (rx/reduce collect-page [])
          (rx/flat-map uz/compress-files)
          (rx/map #(hash-map :type :finish
                             :data (dom/create-uri %)))))))
