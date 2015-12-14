(ns uxbox.data.projects
  (:require [uxbox.rstore :as rs]
            [uxbox.state :as st]
            [uxbox.schema :as sc]
            [bouncer.validators :as v]))

(def ^:static +project-schema+
  {:name [v/required v/string]
   :width [v/required v/integer]
   :height [v/required v/integer]
   :layout [v/required sc/keyword?]})

(defn create-project
  [{:keys [name width height layout] :as data}]
  (sc/validate! +project-schema+ data)
  (println "create-project")
  (reify
    rs/UpdateEvent
    (-apply-update [_ state]
      (let [uuid (random-uuid)
            proj {:id uuid
                  :name name
                  :width width
                  :height height
                  :pages []}]
        (-> state
            (update-in [:projects] conj uuid)
            (update-in [:projects-by-id] assoc uuid {:name name}))))

    IPrintWithWriter
    (-pr-writer [mv writer _]
      (-write writer "#<event:u.s.p/create-project>"))))
