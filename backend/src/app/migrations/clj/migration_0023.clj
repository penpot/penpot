;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.migrations.clj.migration-0023
  (:require
   [app.db :as db]
   [app.util.blob :as blob]))

(defn decode-row
  [{:keys [data] :as row}]
  (when row
    (cond-> row
      data (assoc :data (blob/decode data)))))

(defn retrieve-files
  [conn]
  (->> (db/exec! conn ["select * from file;"])
       (map decode-row)))

(defn retrieve-pages
  [conn file-id]
  (->> (db/query conn :page {:file-id file-id})
       (map decode-row)
       (sort-by :ordering)))

(def empty-file-data
  {:version 1
   :pages []
   :pages-index {}})

(defn pages->data
  [pages]
  (reduce (fn [acc {:keys [id data name] :as page}]
            (let [data (-> data
                           (dissoc :version)
                           (assoc :id id :name name))]
              (-> acc
                  (update :pages (fnil conj []) id)
                  (update :pages-index assoc id data))))
          empty-file-data
          pages))

(defn migrate-file
  [conn {:keys [id] :as file}]
  (let [pages (retrieve-pages conn (:id file))
        data  (pages->data pages)]
    (db/update! conn :file
                {:data (blob/encode data)}
                {:id id})))

(defn migrate
  [conn]
  (let [files (retrieve-files conn)]
    (doseq [file files]
      (when (nil? (:data file))
        (migrate-file conn file)))
    (db/exec-one! conn ["drop table page cascade;"])))
