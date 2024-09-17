;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.renderer-v2
  (:require
   [app.config :as cf]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]))

(defonce internal-module #js {})

(defn on-module-loaded
  [module']
  (let [init-fn (.-default ^js module')]
    (->> (rx/from (init-fn))
         (rx/map (constantly module')))))

(defn- on-module-initialized
  [module]
  (set! internal-module module))

(defn print-msg [msg]
  (let [print-fn (.-print internal-module)]
    (print-fn msg)))

(defn init
  []
  (ptk/reify ::init
    ptk/WatchEvent
    (watch [_ _ _]
      (let [module-uri (assoc cf/public-uri :path "/js/renderer/renderer.js")]
        (->> (rx/from (js/dynamicImport (str module-uri)))
             (rx/mapcat on-module-loaded)
             (rx/tap on-module-initialized)
             (rx/ignore))))))
