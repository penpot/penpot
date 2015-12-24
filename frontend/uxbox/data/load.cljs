(ns uxbox.data.load
  (:require [hodgepodge.core :refer [local-storage]]
            [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
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
    (update-in state [:colors-by-id] assoc uuid color-coll)))

(defn persist-state
  [state]
  (let [pages (into #{} (vals (:pages-by-id state)))
        projects (into #{} (vals (:projects-by-id state)))
        color-colls (into #{} (vals (:colors-by-id state)))]
    (assoc! local-storage :data {:pages pages
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
          (reduce dp/assoc-project $ (:projects data))
          (reduce dp/assoc-page $ (:pages data))
          (reduce assoc-color $ (:color-collections data)))
        state))))

