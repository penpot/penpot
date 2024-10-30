(ns app.main.data.render-wasm
  (:require
   [potok.v2.core :as ptk]))

(defn context-lost
  []
  (ptk/reify ::context-lost
    ptk/UpdateEvent
    (update [_ state]
      (update state :render-state #(assoc % :lost true)))))

(defn context-restored
  []
  (ptk/reify ::context-restored
    ptk/UpdateEvent
    (update [_ state]
      (update state :render-state #(dissoc % :lost)))))
