(ns app.main.data.workspace.text.layout
  (:require
   [app.common.data :as d]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.texts :as dwt]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn- update-text-layout-positions
  [& {:keys [ids]}]
  (ptk/reify ::update-text-layout-positions
    ptk/WatchEvent
    (watch [_ state _]
      (let [objects (wsh/lookup-page-objects state)
            ids (->> ids (filter #(contains? objects %)))]
        (->> (rx/from ids)
             (rx/map #(dwt/v2-update-text-shape-layout :object-id %)))))))

(defn initialize
  []
  (ptk/reify ::initialize
    ptk/WatchEvent
    (watch [_ _ stream]
      (let [stopper (rx/filter (ptk/type? ::finalize) stream)]
        (->> stream
             (rx/filter (ptk/type? :text-layout/update))
             (rx/map deref)
             ;; We buffer the updates to the layout so if there are many changes at the same time
             ;; they are process together. It will get a better performance.
             (rx/buffer-time 100)
             (rx/filter #(d/not-empty? %))
             (rx/map
              (fn [data]
                (let [ids (reduce #(into %1 (:ids %2)) #{} data)]
                  (update-text-layout-positions :ids ids))))
             (rx/take-until stopper))))))

(defn finalize
  []
  (ptk/reify ::finalize))
