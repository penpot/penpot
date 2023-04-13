(ns app.wasm
  (:require
   [app.wasm.resize :as resize] 
   [app.wasm.transform :as transform]))

(defn init!
  []
  (transform/init!)
  (resize/init!))
