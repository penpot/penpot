;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.pages-list
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]))

(defn get-page
  [file-data id]
  (dm/get-in file-data [:pages-index id]))

(defn add-page
  [file-data {:keys [id index] :as page}]
  (-> file-data
      ;; It's legitimate to add a page that is already there, for
      ;; example in an idempotent changes operation.
      (update :pages (fn [pages]
                       (let [exists? (some (partial = id) pages)]
                         (cond
                           exists?      pages
                           (nil? index) (conj pages id)
                           :else        (d/insert-at-index pages index [id])))))
      (update :pages-index assoc id (dissoc page :index))))

(defn pages-seq
  [file-data]
  (vals (:pages-index file-data)))

(defn update-page
  [file-data page-id f]
  (update-in file-data [:pages-index page-id] f))

(defn delete-page
  [file-data page-id]
  (-> file-data
      (update :pages (fn [pages] (filterv #(not= % page-id) pages)))
      (update :pages-index dissoc page-id)))
