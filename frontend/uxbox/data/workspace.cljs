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

(defn toggle-pagesbar
  []
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (println "KAKAKA" (get-in state [:workspace :visible-pagebar]))
      (update-in state [:workspace :visible-pagebar] (fnil not false)))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.s.p/toggle-pagebar>"))))
