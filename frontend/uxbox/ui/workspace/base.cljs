(ns uxbox.ui.workspace.base
  (:require [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as s]
            [uxbox.data.projects :as dp]
            [uxbox.util.lens :as ul]
            [uxbox.ui.util :as util]
            [goog.events :as events])
  (:import goog.events.EventType))

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

;; Scroll

(defonce ^:private scroll-bus (rx/bus))
(defonce scroll-s (rx/dedupe scroll-bus))

(defonce top-scroll-s
  (->> scroll-bus
       (rx/map :top)
       (rx/dedupe)))

(defonce left-scroll-s
  (->> scroll-bus
       (rx/map :left)
       (rx/dedupe)))

(defonce top-scroll (rx/to-atom top-scroll-s))
(defonce left-scroll (rx/to-atom left-scroll-s))

;; Mouse pos

;; (defn- coords-delta
;;   [[old new]]
;;   (let [[oldx oldy] old
;;         [newx newy] new]
;;     [(* 2 (- newx oldx))
;;      (* 2 (- newy oldy))]))

;; (def ^{:doc "A stream of mouse coordinate deltas as `[dx dy]` vectors."}
;;   delta
;;   (s/map coords-delta (s/partition 2 client-position)))

;; DEBUG
;; (rx/on-value (rx/dedupe scroll-bus)
;;              (fn [event]
;;                (println event)))

(defonce mouse-bus (rx/bus))
(defonce mouse-s (rx/dedupe mouse-bus))
(defonce mouse-position (rx/to-atom (rx/throttle 50 mouse-s)))

(defn- mouse-mixin-did-mount
  [own]
  (letfn [(on-mousemove [event]
            (let [canvas (util/get-ref-dom own "canvas")
                  brect (.getBoundingClientRect canvas)
                  offset-x (.-left brect)
                  offset-y (.-top brect)
                  x (.-clientX event)
                  y (.-clientY event)]
              (rx/push! mouse-bus [(- x offset-x)
                                   (- y offset-y)])))]
    (let [key (events/listen js/document EventType.MOUSEMOVE on-mousemove)]
      (js/console.log "mouse-mixin-did-mount" key)
      (assoc own ::eventkey key))))

(defn- mouse-mixin-will-unmount
  [own]
  (let [key (::eventkey own)]
    (js/console.log "mouse-mixin-will-unmount" key)
    (events/unlistenByKey key)
    (dissoc own ::eventkey)))

(defn- mouse-mixin-transfer-state
  [old-own own]
  (let [key (::eventkey old-own)]
    (js/console.log "mouse-mixin-transfer-state" key)
    (assoc own ::eventkey key)))

(def ^:static mouse-mixin
  {:did-mount mouse-mixin-did-mount
   :will-unmount mouse-mixin-will-unmount
   :transfer-state mouse-mixin-transfer-state})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def viewport-height  3000)
(def viewport-width 3000)

(def document-start-x 50)
(def document-start-y 50)

