;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.common.types.pages-list
  (:require
   [app.common.data :as d]))

(defn get-page
  [file-data id]
  (get-in file-data [:pages-index id]))

(defn add-page
  [file-data page]
  (let [; It's legitimate to add a page that is already there,
        ; for example in an idempotent changes operation.
        conj-if-not-exists (fn [pages id]
                             (cond-> pages
                               (not (d/seek #(= % id) pages))
                               (conj id)))]
    (-> file-data
        (update :pages conj-if-not-exists (:id page))
        (update :pages-index assoc (:id page) page))))

(defn pages-seq
  [file-data]
  (vals (:pages-index file-data)))

(defn update-page
  [file-data page-id f]
  (update-in file-data [:pages-index page-id] f))

