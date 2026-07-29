(ns app.main.data.render-wasm
  (:require
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defn context-lost
  []
  (ptk/reify ::context-lost
    ptk/UpdateEvent
    (update [_ state]
      (let [already-lost? (get-in state [:render-state :lost])
            prev-read-only? (get-in state [:workspace-global :read-only?])
            prev-options-mode (get-in state [:workspace-global :options-mode])]
        (-> state
            (update :render-state
                    (fn [render-state]
                      (cond-> (assoc render-state :lost true)
                        (not already-lost?)
                        (assoc :pre-context-lost-read-only? prev-read-only?
                               :pre-context-lost-options-mode prev-options-mode))))
            (assoc-in [:workspace-global :options-mode] :inspect)
            (assoc-in [:workspace-global :read-only?] true))))
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of :interrupt))))

(defn context-restored
  []
  (ptk/reify ::context-restored
    ptk/UpdateEvent
    (update [_ state]
      (let [restored-read-only? (get-in state [:render-state :pre-context-lost-read-only?]
                                        (get-in state [:workspace-global :read-only?]))
            restored-options-mode (get-in state [:render-state :pre-context-lost-options-mode]
                                          (get-in state [:workspace-global :options-mode]))]
        (-> state
            (update :render-state #(dissoc % :lost
                                           :pre-context-lost-read-only?
                                           :pre-context-lost-options-mode))
            (assoc-in [:workspace-global :options-mode] restored-options-mode)
            (assoc-in [:workspace-global :read-only?] restored-read-only?))))
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of :interrupt))))
