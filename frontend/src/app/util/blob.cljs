;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2016 Andrey Antukh <niwi@niwi.nz>

(ns app.util.blob
  "Helpers for work with HTML5 Blob objects.")

;; TODO: DEPRECATED

(defn ^boolean blob?
  [v]
  (instance? js/Blob v))

(defn create
  "Create a blob from content."
  ([content]
   (create content "application/octet-stream"))
  ([content mimetype]
   (js/Blob. #js [content] #js {:type mimetype})))

(defn revoke-uri
  [url]
  (js/URL.revokeObjectURL url))

(defn create-uri
  "Create a url from blob."
  [b]
  {:pre [(blob? b)]}
  (js/URL.createObjectURL b))
