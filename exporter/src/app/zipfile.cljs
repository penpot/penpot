(ns app.zipfile
  (:require
   ["jszip" :as jszip]))

(defn create
  []
  (new jszip))

(defn add!
  [zfile name data]
  (.file ^js zfile name data)
  zfile)

