(ns bench.engine (:require [datascript.core :as d]))
(def engine :datascript)
(defn build [schema tx] (d/db-with (d/empty-db schema) tx))
(def q d/q)
