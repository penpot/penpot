(ns uxbox.data.load
  (:require [hodgepodge.core :refer [local-storage]]
            [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [uxbox.state.project :as stpr]
            [uxbox.data.projects :as dp]
            [bouncer.validators :as v]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn assoc-color
  "A reduce function for assoc the project
  to the state map."
  [state color-coll]
  (let [uuid (:id color-coll)]
    (update state :colors-by-id assoc uuid color-coll)))

(defn assoc-shape
  [state shape]
  (let [id (:id shape)]
    (update state :shapes-by-id assoc id shape)))

(defn persist-state
  [state]
  (let [pages (into #{} (vals (:pages-by-id state)))
        projects (into #{} (vals (:projects-by-id state)))
        shapes (into #{} (vals (:shapes-by-id state)))
        color-colls (into #{} (vals (:colors-by-id state)))]

    (assoc! local-storage :data {:pages pages
                                 :shapes shapes
                                 :projects projects
                                 :color-collections color-colls})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn load-data
  "Load data from local storage."
  []
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (if-let [data (get local-storage :data nil)]
        (as-> state $
          (reduce stpr/assoc-project $ (:projects data))
          (reduce stpr/assoc-page $ (:pages data))
          (reduce assoc-color $ (:color-collections data))
          (reduce assoc-shape $ (:shapes data))
          (assoc $ :auth (:auth data)))
        state))))

