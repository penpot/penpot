(ns app.common.thumbnails
  (:require
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

(defn fmt-object-id
  "Returns ids formatted as a string (object-id)"
  [file-id page-id frame-id tag]
  (str/ffmt "%/%/%/%" file-id page-id frame-id tag))

(defn file-id?
  "Returns ids formatted as a string (file-id)"
  [object-id file-id]
  (str/starts-with? object-id (str/concat file-id "/")))

(defn get-file-id
  [object-id]
  (uuid/uuid (str/slice object-id 0 (str/index-of object-id "/"))))
