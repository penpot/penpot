(ns uxbox.main.state.clipboard)

(defonce ^:private ^:const +max-items+ 5)

(defn conj-item
  [state item]
  (if-let [project (get-in state [:workspace :project])]
    (let [queue (get-in state [:clipboard project] #queue [])
          queue (conj queue item)]
      (assoc-in state [:clipboard project]
                (if (> (count queue) +max-items+)
                  (pop queue)
                  queue)))
    (do
      (js/console.warn "no active project for manage clipboard.")
      state)))
