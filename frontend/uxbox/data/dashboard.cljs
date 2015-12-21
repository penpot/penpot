(ns uxbox.data.dashboard
  (:require [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [uxbox.time :as time]
            [bouncer.validators :as v]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn assoc-page
  "A reduce function for assoc the page
  to the state map."
  [state page]
  (let [uuid (:id page)]
    (update-in state [:pages-by-id] assoc uuid page)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn merge-if-not-exists
  [map & maps]
  (let [result (transient map)]
    (loop [maps maps]
      (if-let [nextval (first maps)]
        (do
          (run! (fn [[key value]]
                  (when-not (contains? result key)
                    (assoc! result key value)))
                nextval)
          (recur (rest maps)))
        (persistent! result)))))

(defn initialize
  [section]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (as-> state $
        (assoc-in $ [:dashboard :section] section)
        (update $ :dashboard merge-if-not-exists
                {:collection-type :builtin
                 :collection-id 1})))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.d.d/initialize>"))))

(defn set-collection-type
  [type]
  {:pre [(contains? #{:builtin :own} type)]}
  (letfn [(select-first [state]
            (if (= type :builtin)
              (assoc-in state [:dashboard :collection-id] 1)
              (let [coll (sort-by :id (vals (:colors-by-id state)))]
                (assoc-in state [:dashboard :collection-id] (:id (first coll))))))]
    (reify
      rs/UpdateEvent
      (-apply-update [_ state]
        (as-> state $
          (assoc-in $ [:dashboard :collection-type] type)
          (select-first $)))

      IPrintWithWriter
      (-pr-writer [mv writer _]
        (-write writer "#<event:u.d.d/set-collection-type>")))))

(defn set-collection
  [id]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (println "set-collection" id)
      (assoc-in state [:dashboard :collection-id] id))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.d.d/set-collection>"))))
