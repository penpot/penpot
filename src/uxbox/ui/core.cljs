(ns uxbox.ui.core
  (:require [beicon.core :as rx]
            [cuerdas.core :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Actions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce lock (atom ""))
(defonce actions-s (rx/bus))

(defn acquire-action!
  ([type]
   (acquire-action! type nil))
  ([type payload]
   (when (empty? @lock)
     (reset! lock type)
     (rx/push! actions-s {:type type :payload payload}))))

(defn release-action!
  ([type]
   (when (str/contains? @lock type)
     (rx/push! actions-s {:type ""})
     (reset! lock "")))
  ([type & more]
   (run! release-action! (cons type more))))
