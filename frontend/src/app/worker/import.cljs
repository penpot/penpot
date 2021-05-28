;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.worker.import
  (:require
   [app.common.data :as d]
   [app.common.file-builder :as fb]
   [app.util.zip :as uz]
   [app.worker.impl :as impl]
   [beicon.core :as rx]
   [cuerdas.core :as str]
   [tubax.core :as tubax]))

(defn parse-file-name
  [dir]
  (if (str/ends-with? dir "/")
    (subs dir 0 (dec (count dir)))
    dir))

(defn parse-page-name [path]
  (let [[file page] (str/split path "/")]
    (str/replace page ".svg" "")))

(defn import-page [file {:keys [path data]}]
  (let [page-name (parse-page-name path)]
    (-> file
        (fb/add-page page-name))))

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
            (->> file-str
                 (rx/filter #(str/starts-with? (:path %) dir))
                 (rx/reduce import-page (fb/create-file (parse-file-name dir))))))

         (rx/map #(select-keys % [:id :name])))))
