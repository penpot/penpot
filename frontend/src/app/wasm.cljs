(ns app.wasm
  (:require
   [promesa.core :as p]))

(defonce instance (atom nil))

(defn load-wasm
  "Loads a WebAssembly module"
  [uri]
  (-> 
   (p/let [response (js/fetch uri)
          array-buffer (.arrayBuffer response)
          assembly (.instantiate js/WebAssembly array-buffer)]
    assembly)
   (p/catch (fn [error] (prn "error: " error)))))

(defn init!
  "Initializes WebAssembly module"
  []
  (p/then
   (load-wasm "wasm/add.wasm")
   (fn [assembly]
     (let [operations (js/Int32Array.
                       assembly.instance.exports.memory.buffer ;; buffer we want to use
                       assembly.instance.exports.operations.value ;; offset of pointer 'operations'
                       (* 2048 12))]
       (aset operations 0 2)
       (aset operations 1 2)
       (.set operations #js [4 5 -1] 3)
       (js/console.time "compute")
       (assembly.instance.exports.compute)
       (js/console.timeEnd "compute")
       (js/console.log assembly)
     )
     (reset! instance assembly.instance))))
