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

(defn initialize
  [section]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (assoc state :dashboard {:section section
                               :collection-type :builtin
                               :collection-id nil}))
    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.d.d/initialize>"))))

(defn set-collection-type
  [type]
  {:pre [(contains? #{:builtin :own} type)]}
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (as-> state $
        (assoc-in $ [:dashboard :collection-type] type)
        (assoc-in $ [:dashboard :collection-id] nil)))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.d.d/set-collection-type>"))))

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
