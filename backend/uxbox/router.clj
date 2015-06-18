(ns uxbox.router
  (:refer-clojure :exclude [error-handler])
  (:require [uxbox.impl.routing :as rt]
            [com.stuartsierra.component :as component]
            [catacumba.components :refer (catacumba-server assoc-routes!)]
            [cats.core :as m]
            [promissum.core :as p]))

(defmulti handler
  (fn [context frame]
    [(:cmd frame)
     (:dest frame)]))

;; (defmethod handler [:novelty :auth]
;;   [context frame]
;;   (let [state (:state context)
;;         body (:body frame)]
;;     (m/mlet [user (authenticate body)]
;;       (swap! state assoc :user user)
;;       (m/return (rt/response {:ok true})))))

;; (defmethod handler [:query :project]
;;   [context frame]
;;   (let [state (:state context)
;;         body (:body frame)]
;;     (m/mlet [proj (query-project body)]
;;       (m/return (rt/response proj)))))

(defrecord WebComponent [config server]
  component/Lifecycle
  (start [this]
    (let [routes [[:any "api" (rt/router handler)]]]
      (assoc-routes! server ::web routes)))

  (stop [this]
    ;; noop
    ))

(defn component
  []
  (WebComponent. nil nil))
