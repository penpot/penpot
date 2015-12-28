(ns uxbox.data.workspace
  (:require [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [uxbox.time :as time]
            [bouncer.validators :as v]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn toggle-tool
  [toolname]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [key (keyword (str (name toolname) "-enabled"))]
        (update-in state [:workspace key] (fnil not false))))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.s.p/toggle-tool>"))))

(defn toggle-toolbox
  [toolname]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [key (keyword (str (name toolname) "-toolbox-enabled"))
            val (get-in state [:workspace key] false)
            state (assoc-in state [:workspace key] (not val))]
        (if val
          (update-in state [:workspace :toolboxes] disj toolname)
          (update-in state [:workspace :toolboxes] conj toolname))))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.s.p/toggle-toolbox>"))))

(defn initialize
  [projectid pageid]
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [s {:project projectid
               :toolboxes #{}
               :selected #{}
               :page pageid}]
        (assoc state :workspace s)))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.s.p/initialize>"))))
