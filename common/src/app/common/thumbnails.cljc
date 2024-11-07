(ns app.common.thumbnails
  (:require
   [app.common.uuid :as uuid]
   [cuerdas.core :as str]))

(defn fmt-object-id
  "Returns ids formatted as a string (object-id)"
  ([object]
   (fmt-object-id (:file-id object)
                  (:page-id object)
                  (:frame-id object)
                  (:tag object)))
  ([file-id page-id frame-id tag]
   (str/ffmt "%/%/%/%" file-id page-id frame-id tag)))

;; FIXME: rename to a proper name

(defn file-id?
  "Returns ids formatted as a string (file-id)"
  [object-id file-id]
  (str/starts-with? object-id (str/concat file-id "/")))

(defn parse-object-id
  [object-id]
  (let [[file-id page-id frame-id tag] (str/split object-id "/")]
    {:file-id (parse-uuid file-id)
     :page-id (parse-uuid page-id)
     :frame-id (parse-uuid frame-id)
     :tag tag}))

(defn get-file-id
  [object-id]
  (uuid/uuid (str/slice object-id 0 (str/index-of object-id "/"))))
