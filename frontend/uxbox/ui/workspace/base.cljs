(ns uxbox.ui.workspace.base
  (:require [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.data.projects :as dp]
            [uxbox.util.lens :as ul]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static project-state
  (as-> (ul/dep-in [:projects-by-id] [:workspace :project]) $
    (l/focus-atom $ s/state)))

(def ^:static page-state
  (as-> (ul/dep-in [:pages-by-id] [:workspace :page]) $
    (l/focus-atom $ s/state)))

(def ^:static pages-state
  (as-> (ul/getter #(let [pid (get-in % [:workspace :project])]
                        (dp/project-pages % pid))) $
    (l/focus-atom $ s/state)))

(def ^:static workspace-state
  (as-> (l/in [:workspace]) $
    (l/focus-atom $ s/state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Streams
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce scroll-bus (rx/bus))
(defonce top-scroll-s (rx/dedupe (rx/map :top scroll-bus)))
(defonce left-scroll-s (rx/dedupe (rx/map :left scroll-bus)))

(defonce top-scroll (rx/to-atom top-scroll-s))
(defonce left-scroll (rx/to-atom left-scroll-s))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def viewport-height  3000)
(def viewport-width 3000)

(def document-start-x 50)
(def document-start-y 50)

