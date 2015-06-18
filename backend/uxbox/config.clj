(ns uxbox.config
  (:require [clojure.java.io :as io]
            [nomad :refer [read-config]]))

(defn component
  [path]
  (nomad/read-config (io/resource path)))
