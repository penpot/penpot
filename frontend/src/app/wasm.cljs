(ns app.wasm
  (:require
   [promesa.core :as p]))

(defonce assembly (atom nil))

(defn load-wasm
  "Loads a WebAssembly module"
  [uri]
  (-> 
   (p/let [response (js/fetch uri)
          array-buffer (.arrayBuffer response)
          assembly (.instantiate js/WebAssembly array-buffer (js-obj "env" (js-obj)))]
    assembly)
   (p/catch (fn [error] (prn "error: " error)))))

(defn init!
  "Initializes WebAssembly module"
  []
  (p/then
   (load-wasm "wasm/build/resize.wasm")
   (fn [asm]
     (reset! assembly 
             (js-obj "instance" asm.instance 
                     "module" asm.module
                     "exports" asm.instance.exports)))))

(defn get-handler-id
  [handler]
  (case handler
    :top 0
    :top-right 1
    :right 2
    :bottom-right 3
    :bottom 4
    :bottom-left 5
    :left 6
    :top-left 7))

(defn resize
  [handler ids shape]
  ;; TODO: Tenemos que resolver los diferentes shapes
  ;; para subirlos al módulo de WebAssembly.
  (when @assembly
    (let [asm @assembly]
      (js/console.log (clj->js ids) (clj->js shape))
      (asm.exports.resize (get-handler-id handler) ids shape))))
