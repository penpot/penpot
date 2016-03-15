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
  [state proj]
  (let [uuid (:id proj)]
    (update-in state [:projects-by-id] dissoc uuid)))

(defn assoc-page
  "A reduce function for assoc the page
  to the state map."
  [state page]
  (let [uuid (:id page)]
    (update-in state [:pages-by-id] assoc uuid page)))

(defn project-pages
  "Get a ordered list of pages that
  belongs to a specified project."
  [state projectid]
  (->> (vals (:pages-by-id state))
       (filter #(= projectid (:project %)))
       (sort-by :created)))

