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

(defn dissoc-page-shapes
  [state id]
  (let [shapes (get-in state [:shapes-by-id])]
    (assoc state :shapes-by-id (reduce-kv (fn [acc k v]
                                            (if (= (:page v) id)
                                              (dissoc acc k)
                                              acc))
                                          shapes
                                          shapes))))
(defn assoc-page
  [state {:keys [id] :as page}]
  (assoc-in state [:pagedata-by-id id] page))

(defn dissoc-page
  [state id]
  (update state :pagedata-by-id dissoc id))

(defn pack-page
  "Return a packed version of page object ready
  for send to remore storage service."
  [state id]
  (let [page (get-in state [:pages-by-id id])
        xf (filter #(= (:page (second %)) id))
        shapes (into {} xf (:shapes-by-id state))]
    (-> page
        (assoc-in [:data :shapes] (into [] (:shapes page)))
        (assoc-in [:data :shapes-by-id] shapes)
        (update-in [:data] dissoc :items)
        (dissoc :shapes))))

(defn unpack-page
  "Unpacks packed page object and assocs it to the
  provided state."
  [state page]
  (let [data (:data page)
        shapes (:shapes data)
        shapes-by-id (:shapes-by-id data)
        page (-> page
                 (dissoc page :data)
                 (assoc :shapes shapes))]
    (-> state
        (update :shapes-by-id merge shapes-by-id)
        (update-in [:pages-by-id] assoc (:id page) page))))

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
       (sort-by :created-at)))

