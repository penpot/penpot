;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.renderer
  "Common renderer interface, validated with malli from day one.

  Dispatches one render at a time to the browser or the headless
  driver by the `:is-wasm` flag of the params. `env` carries
  everything the params do not freeze: the browser context
  (`:browser-pool`, `:base-uri`, `:public-uri`, `:svgo?`), the wasm
  pool instance (`:wasm-pool`, the `{:pool :timeout-ms}` map the pool
  service owns) and the injected cancel source (`:cancel-source`,
  the `:cancel-signal`/`:cancelled?`/`:on-cancel` fns)."
  (:require
   [app.common.schema :as sm]
   [exporter.renderer.browser :as browser]
   [exporter.renderer.wasm :as wasm]))

(def schema:type
  [:enum :png :jpeg :webp :pdf :svg])

(def schema:object
  [:map
   [:id :string]
   [:name :string]
   [:suffix :string]
   [:filename :string]
   [:share-id {:optional true} ::sm/uuid]])

(def schema:objects
  [:vector {:min 1} schema:object])

(def schema:render-params
  [:map
   [:file-id ::sm/uuid]
   [:page-id ::sm/uuid]
   [:scale ::sm/number]
   [:token :string]
   [:type schema:type]
   [:objects schema:objects]
   [:is-wasm {:optional true} ::sm/boolean]
   [:job-id {:optional true} ::sm/uuid]])

(def ^:private check-params
  (sm/check-fn schema:render-params :hint "invalid render params"))

(def ^:private check-callback
  (sm/check-fn ::sm/fn :hint "on-object must be a fn"))

(defn- browser-ctx
  [env]
  (select-keys env [:browser-pool :base-uri :public-uri :svgo?]))

(defn headless?
  "Whether `params` renders with render-wasm rather than a browser."
  [{:keys [is-wasm]}]
  (boolean is-wasm))

(defn ^:async render
  [env params on-object]
  (check-params params)
  (check-callback on-object)
  (if (headless? params)
    (await (wasm/render (:wasm-pool env) (:cancel-source env) params on-object))
    (await (browser/render (browser-ctx env) params on-object))))

(defn ^:async with-scope
  "Runs `f`, a fn of a render fn with the same signature as `render`.
  Exports that render headless share one worker for the whole call
  instead of acquiring one per render; the browser backend keeps
  rendering them one checkout at a time."
  [env exports f]
  (if (some headless? exports)
    (await (wasm/with-scope (:wasm-pool env) (:cancel-source env)
             (:job-id (first exports))
             (fn [leased]
               (f (fn [params on-object]
                    (check-params params)
                    (check-callback on-object)
                    (if (headless? params)
                      (leased params on-object)
                      (browser/render (browser-ctx env) params on-object)))))))
    (await (f (fn [params on-object]
                (render env params on-object))))))
