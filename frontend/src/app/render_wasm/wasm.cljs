(ns app.render-wasm.wasm)

(defonce internal-frame-id nil)
(defonce internal-module #js {})
(defonce serializers #js {})