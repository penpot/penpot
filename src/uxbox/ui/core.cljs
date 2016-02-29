(ns uxbox.ui.core
  (:require [beicon.core :as rx]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Actions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce actions-lock (atom :nothing))
(defonce actions-s (rx/bus))

;; TODO: implement that as multimethod for add specific validation
;; layer for different kind of action payloads

(defn acquire-action!
  ([type]
   (acquire-action! type nil))
  ([type payload]
   (when-let [result (compare-and-set! actions-lock :nothing type)]
     (rx/push! actions-s {:type type :payload payload}))))

(defn release-action!
  [type]
  (when-let [result (compare-and-set! actions-lock type :nothing)]
    (rx/push! actions-s {:type :nothing})))

(defn release-all-actions!
  []
  (reset! actions-lock :nothing)
  (rx/push! actions-s {:type :nothing}))
