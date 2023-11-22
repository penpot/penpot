(ns app.main.ui.cosmos.renderer
  (:require
    ["react-cosmos-dom" :as rdc]
    ;; ["../../../../cosmos.imports.js" :as ci]
    [rumext.v2 :as mf]))

;; import * as fixture0 from './app/main/ui/components/buttons/primary_button.fixture.js';

;; export const rendererConfig = {
;;   "playgroundUrl": "http://localhost:5050",
;;   "rendererUrl": "http://localhost:3449/#/cosmos"
;; };

;; const fixtures = {
;;   'src/app/main/ui/components/buttons/primary_button.fixture.js': { module: fixture0 }
;; };

;; const decorators = {};

;; export const moduleWrappers = {
;;   lazy: false,
;;   fixtures,
;;   decorators
;; };

(def fixture0 #js {
  "Default": (mf/html [:button "Simple HTMLbutton"])
})

(def fixtures #js {
  "src/app/main/ui/components/buttons/primary_button.fixture.js": #js {:module fixture0}
})

(def module-wrappers #js {
  :lazy false,
  :fixtures fixtures,
  :decorators #js {}
})

(def renderer-config #js {
  "playgroundUrl": "http://localhost:5050",
  "rendererUrl": "http://localhost:3449/#/cosmos"
})


(mf/defc renderer
   {::mf/wrap-props false} [] (do
    (mf/with-effect (fn []
      (rdc/mountDomRenderer js# {:renderer-config renderer-config :module-wrappers module-wrappers})))
    []))