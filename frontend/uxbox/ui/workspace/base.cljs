(ns uxbox.ui.workspace.base
  (:require [beicon.core :as rx]
            [cats.labs.lens :as l]
            [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.data.projects :as dp]
            [uxbox.data.workspace :as dw]
            [uxbox.util.lens :as ul]
            [uxbox.ui.util :as util]
            [goog.events :as events])
  (:import goog.events.EventType))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:static project-state
  (as-> (ul/dep-in [:projects-by-id] [:workspace :project]) $
    (l/focus-atom $ st/state)))

(def ^:static page-state
  (as-> (ul/dep-in [:pages-by-id] [:workspace :page]) $
    (l/focus-atom $ st/state)))

(def ^:static pages-state
  (as-> (ul/getter #(let [pid (get-in % [:workspace :project])]
                        (dp/project-pages % pid))) $
    (l/focus-atom $ st/state)))

(def ^:static workspace-state
  (as-> (l/in [:workspace]) $
    (l/focus-atom $ st/state)))

(def ^:static active-toolboxes-state
  (as-> (l/in [:workspace :toolboxes]) $
    (l/focus-atom $ st/state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scroll Stream
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private scroll-b (rx/bus))

(defonce scroll-s
  (as-> scroll-b $
    (rx/merge $ (rx/of {:top 0 :left 0}))
    (rx/dedupe $)))

(defonce scroll-top-s
  (->> scroll-s
       (rx/map :top)
       (rx/dedupe)))

(defonce scroll-left-s
  (->> scroll-s
       (rx/map :left)
       (rx/dedupe)))

(defonce scroll-top (rx/to-atom scroll-top-s))
(defonce scroll-left (rx/to-atom scroll-left-s))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mouse Position Stream
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce shapes-dragging? (atom false))
(defonce selrect-dragging? (atom false))
(defonce selrect-pos (atom nil))

(defonce mouse-b (rx/bus))
(defonce mouse-s
  (->> mouse-b
       (rx/filter #(= (:id %) (:id @page-state)))
       (rx/map :coords)))

;; Deltas

(defn- coords-delta
  [[old new]]
  (let [[oldx oldy] old
        [newx newy] new]
    [(- newx oldx)
     (- newy oldy)]))

(defonce mouse-delta-s
  (->> mouse-s
       (rx/sample 10)
       (rx/buffer 2 1)
       (rx/map coords-delta)))

(defonce $$drag-subscription$$
  (as-> mouse-delta-s $
    (rx/filter #(deref shapes-dragging?) $)
    (rx/on-value $ (fn [delta]
                     (let [pageid (get-in @st/state [:workspace :page])
                           selected (get-in @st/state [:workspace :selected])
                           page (get-in @st/state [:pages-by-id pageid])]
                       (doseq [sid (filter selected (:shapes page))]
                         (rs/emit! (dw/move-shape sid delta))))))))

(defn selrect->rect
  [data]
  (let [start (:start data)
        current (:current data )
        start-x (min (first start) (first current))
        start-y (min (second start) (second current))
        current-x (max (first start) (first current))
        current-y (max (second start) (second current))
        width (- current-x start-x)
        height (- current-y start-y)]
    {:x start-x
     :y start-y
     :width (- current-x start-x)
     :height (- current-y start-y)}))

(defonce $$selrect-subscription-0$$
  (let [ss (as-> (rx/from-atom selrect-dragging?) $
             (rx/dedupe $)
             (rx/merge $ (rx/of false))
             (rx/buffer 2 1 $)
             (rx/share $))]
    (as-> ss $
      (rx/filter #(= (vec %) [false true]) $)
      (rx/with-latest-from vector mouse-s $)
      (rx/on-value $ (fn [[_ pos]]
                       (swap! selrect-pos assoc
                              :start pos
                              :current pos))))
    (as-> ss $
      (rx/filter #(= (vec %) [true false]) $)
      (rx/on-value $ (fn []
                       (let [selrect (selrect->rect @selrect-pos)]
                         (rs/emit! (dw/select-shapes selrect))
                         (reset! selrect-pos nil)))))))

(defonce $$selrect-subscription-1$$
  (as-> mouse-s $
    (rx/filter #(deref selrect-dragging?) $)
    (rx/on-value $ (fn [pos]
                     (swap! selrect-pos assoc :current pos)))))

;; Materialized views

(defonce mouse-position
  (->> mouse-s
       (rx/sample 50)
       (rx/to-atom)))

(defonce bounding-rect (atom {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def viewport-height  2048)
(def viewport-width 2048)

(def document-start-x 50)
(def document-start-y 50)

