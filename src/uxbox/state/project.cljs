(ns uxbox.state.project
  "A collection of functions for manage shapes insinde the state.")

(defn assoc-project
  "A reduce function for assoc the project
  to the state map."
  [state proj]
  (let [id (:id proj)]
    (update-in state [:projects-by-id id] merge proj)))

(defn dissoc-project
  "A reduce function for dissoc the project
  from the state map."
  [state id]
  (update-in state [:projects-by-id] dissoc id))

(defn assoc-page
  "A reduce function for assoc the page
  to the state map."
  [state page]
  (let [uuid (:id page)]
    (update-in state [:pages-by-id] assoc uuid page)))

(defn dissoc-page-shapes
  [state id]
  (let [shapes (get-in state [:shapes-by-id])]
    (assoc state :shapes-by-id (reduce-kv (fn [acc k v]
                                            (if (= (:page v) id)
                                              (dissoc acc k)
                                              acc))
                                          shapes
                                          shapes))))

(defn dissoc-page
  "Remove page and all related stuff from the state."
  [state id]
  (-> state
      (update :pages-by-id dissoc id)
      (dissoc-page-shapes id)))

(defn project-pages
  "Get a ordered list of pages that
  belongs to a specified project."
  [state projectid]
  (->> (vals (:pages-by-id state))
       (filter #(= projectid (:project %)))
       (sort-by :created)))

