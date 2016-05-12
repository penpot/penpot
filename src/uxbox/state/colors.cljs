(ns uxbox.state.colors
  "A collection of functions for manage dashboard data insinde the state.")

(defn assoc-collection
  "A reduce function for assoc the color collection
  to the state map."
  [state coll]
  (let [id (:id coll)]
    (assoc-in state [:colors-by-id id] coll)))

(defn dissoc-collection
  "A reduce function for dissoc the color collection
  to the state map."
  [state id]
  (update state :colors-by-id dissoc id))

(defn select-first-collection
  "A reduce function for select the first color collection
  to the state map."
  [state]
  (let [colls (sort-by :id (vals (:colors-by-id state)))]
    (assoc-in state [:dashboard :collection-id] (:id (first colls)))))
