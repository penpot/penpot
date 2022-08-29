;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.pages-list
  (:require
   [app.common.data :as d]
   [app.common.pages.helpers :as cph]))

(defn get-page
  [file-data id]
  (get-in file-data [:pages-index id]))

(defn add-page
  [file-data page]
  (let [index (:index page)
        page (dissoc page :index)

        ; It's legitimate to add a page that is already there,
        ; for example in an idempotent changes operation.
        add-if-not-exists (fn [pages id]
                            (cond
                              (d/seek #(= % id) pages) pages
                              (nil? index)             (conj pages id)
                              :else                    (cph/insert-at-index pages index [id])))]
    (-> file-data
        (update :pages add-if-not-exists (:id page))
        (update :pages-index assoc (:id page) page))))

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

