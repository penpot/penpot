(ns uxbox.data.load
  (:require [hodgepodge.core :refer [local-storage]]
            [uxbox.rstore :as rs]
            [uxbox.router :as r]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [uxbox.data.projects :as dp]
            [bouncer.validators :as v]))

(defn load-data
  "Load data from local storage."
  []
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (if-let [data (get local-storage :data nil)]
        (as-> state $
          (reduce dp/assoc-project $ (:projects data))
          (reduce dp/assoc-page $ (:pages data)))
        state))))

(defn persist-state
  [state]
  (let [pages (into #{} (vals (:pages-by-id state)))
        projects (into #{} (vals (:projects-by-id state)))]
    (assoc! local-storage :data {:pages pages
                                 :projects projects})))
