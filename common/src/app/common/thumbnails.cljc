(ns app.common.thumbnails
  (:require [cuerdas.core :as str]))

(defn fmt-object-id
  "Returns ids formatted as a string (object-id)"
  [file-id page-id frame-id tag]
  (str/ffmt "%/%/%/%" file-id page-id frame-id tag))
