(ns app.wasm
  (:require
   [app.common.logging :as log]
   [app.wasm.resize :as resize]
   [app.wasm.transform :as transform]
   [promesa.core :as p]))

(log/set-level! :debug)

(defn init!
  []
  (->> (p/all [(transform/init!)
               (resize/init!)])
       (p/fmap (fn [_]
                 (log/info :hint "WASM modules initialized")))))

