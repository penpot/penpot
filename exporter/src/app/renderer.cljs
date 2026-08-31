;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.renderer
  "Common renderer interface."
  (:require
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.config :as cf]
   [app.renderer.bitmap :as rb]
   [app.renderer.pdf :as rp]
   [app.renderer.svg :as rs]
   [app.renderer.wasm :as rw]
   [cljs.spec.alpha :as s]))

(s/def ::name ::us/string)
(s/def ::suffix ::us/string)
(s/def ::type #{:png :jpeg :webp :pdf :svg})
(s/def ::page-id ::us/uuid)
(s/def ::file-id ::us/uuid)
(s/def ::share-id ::us/uuid)
(s/def ::scale ::us/number)
(s/def ::token ::us/string)
(s/def ::filename ::us/string)
(s/def ::is-wasm ::us/boolean)
(s/def ::job-id ::us/uuid)

(s/def ::object
  (s/keys :req-un [::id ::name ::suffix ::filename]
          :opt-un [::share-id]))

(s/def ::objects
  (s/coll-of ::object :min-count 1))

(s/def ::render-params
  (s/keys :req-un [::file-id ::page-id ::scale ::token ::type ::objects]
          :opt-un [::is-wasm ::job-id]))

(defn headless?
  "Whether `params` renders with render-wasm rather than a browser."
  [{:keys [type is-wasm]}]
  (and is-wasm (contains? cf/flags :wasm-export) (not= :svg type)))

(defn render
  [{:keys [type is-wasm] :as params} on-object]
  (us/verify ::render-params params)
  (us/verify fn? on-object)
  (let [headless? (headless? params)]
    (when is-wasm
      (l/info :hint "render"
              :type type
              :wasm-export (contains? cf/flags :wasm-export)
              :backend (if headless? "wasm" "browser")))
    (if headless?
      (rw/render params on-object)
      (case type
        :png  (rb/render params on-object)
        :jpeg (rb/render params on-object)
        :webp (rb/render params on-object)
        :pdf  (rp/render params on-object)
        :svg  (rs/render params on-object)))))

(defn with-scope
  "Runs `f`, a fn of a render fn with the same signature as `render`. Exports
  that render headless share one worker for the whole call instead of acquiring
  one per render; the browser backend keeps rendering them in parallel."
  [exports f]
  (if (some headless? exports)
    (rw/with-scope (:job-id (first exports))
      (fn [render-leased]
        (f (fn [params on-object]
             (us/verify ::render-params params)
             (us/verify fn? on-object)
             (if (headless? params)
               (render-leased params on-object)
               (render params on-object))))))
    (f render)))

