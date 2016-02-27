(ns uxbox.ui.core
  (:require [beicon.core :as rx]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Actions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce actions-lock (atom :nothing))
(defonce actions-s (rx/bus))

(defn acquire-action!
  [type]
  (when-let [result (compare-and-set! actions-lock :nothing type)]
    ;; (println "acquire-action!" type)
    (rx/push! actions-s type)))

(defn release-action!
  [type]
  (when-let [result (compare-and-set! actions-lock type :nothing)]
    ;; (println "release-action!" type)
    (rx/push! actions-s :nothing)))
