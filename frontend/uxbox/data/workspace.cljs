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
