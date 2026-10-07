;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns exporter.browser
  "The browser pool service and the page API its pages speak.

  The pool is a system component (`:exporter.browser/pool`): `init-key`
  builds a `generic-pool` of Chromium browsers, `halt-key` drains it.
  Everything else here is the plain page API the renderers use; none of
  it touches the pool, so none of it needs the system.

  There are no promesa chains in this namespace: Playwright and
  `generic-pool` already speak native promises, and `await` covers the
  rest."
  (:refer-clojure :exclude [eval])
  (:require
   ["generic-pool" :as gp]
   ["generic-pool/lib/errors.js" :as gpe]
   ["playwright" :as pw]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.util.object :as obj]
   [exporter.utils.system :as system]))

(l/set-level! :trace)

(def ^:private TimeoutError gpe/TimeoutError)

;; --- PAGE API

(def default-timeout 30000)
(def default-viewport-width 1920)
(def default-viewport-height 1080)
(def default-user-agent
  (str "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/99.0.3729.169 Safari/537.36"))

(defn create-cookies
  [uri {:keys [name token] :or {name "auth-token"}}]
  (let [domain (str (:host uri)
                    (when (:port uri)
                      (str ":" (:port uri))))]
    #js [#js {:domain domain
              :path "/"
              :name name
              :value token}]))

(defn nav
  ([page url] (nav page url nil))
  ([page url {:keys [wait-until timeout] :or {wait-until "networkidle" timeout 20000}}]
   (.goto ^js page (str url) #js {:waitUntil wait-until :timeout timeout})))

(defn sleep
  [page ms]
  (.waitForTimeout ^js page ms))

(defn- ^:async wait-for-fonts-async
  [page timeout]
  (try
    (await (.waitForFunction ^js page
                             "async () => {
                                if (!document.fonts) return true;
                                try {
                                  await Promise.all(Array.from(document.fonts, (face) => face.load()));
                                } catch (e) {}
                                await document.fonts.ready;
                                return document.fonts.status === 'loaded';
                              }"
                             nil
                             #js {:timeout timeout}))
    (catch :default cause
      (l/warn :hint "wait-for-fonts timed out; continuing anyway"
              :cause (ex-message cause))
      nil)))

(defn wait-for-fonts
  "Wait until the browser has finished loading all fonts.

  Checking `document.fonts.status === 'loaded'` on its own is not enough:
  the render page injects its `@font-face` rules asynchronously and the
  browser only loads a face lazily, when some painted text actually uses
  it. A face that is declared but not yet requested stays in the `unloaded`
  state, which does NOT keep `document.fonts.status` at `loading`, so the
  check can pass before any real font glyphs are available. The export is
  then captured with a (usually wider) fallback font, and auto-width text
  laid out with the real font metrics overflows its bounds and gets clipped.

  To avoid that we explicitly request every declared face and await
  `document.fonts.ready` before checking the status."
  ([page] (wait-for-fonts page nil))
  ([page {:keys [timeout] :or {timeout 15000}}]
   (wait-for-fonts-async page timeout)))

(defn wait-for
  ([locator] (wait-for locator nil))
  ([locator {:keys [state timeout] :or {state "visible" timeout 10000}}]
   (.waitFor ^js locator #js {:state state :timeout timeout})))

(defn screenshot
  ([frame] (screenshot frame {}))
  ([frame {:keys [full-page? omit-background? type quality path]
           :or {type "png" full-page? false omit-background? false quality 95}}]
   (let [options (-> (obj/new)
                     (obj/set! "type" (name type))
                     (obj/set! "omitBackground" omit-background?)
                     (cond-> path (obj/set! "path" path))
                     (cond-> (= "jpeg" type) (obj/set! "quality" quality))
                     (cond-> full-page?      (-> (obj/set! "fullPage" true)
                                                 (obj/set! "clip" nil))))]
     (.screenshot ^js frame options))))

(defn emulate-media
  [page {:keys [media]}]
  (.emulateMedia ^js page #js {:media media})
  page)

(defn pdf
  ([page] (pdf page {}))
  ([page {:keys [scale path page-ranges]
          :or {page-ranges "1"
               scale 1}}]
   (.pdf ^js page #js {:path path
                       :scale scale
                       :pageRanges page-ranges
                       :printBackground true
                       :preferCSSPageSize true})))

(defn eval
  [frame f]
  (.evaluate ^js frame f))

(defn select
  [frame selector]
  (.locator ^js frame selector))

(defn select-all
  [frame selector]
  (.$$ ^js frame selector))

;; --- POOL SERVICE

(def defaults
  "Static pool defaults. The sizing (`::max`) mirrors the worker
  concurrency default; the wiring layer passes the env-derived value."
  {::max             2
   ::acquire-timeout 10000
   ::idle-timeout    10000
   ::launch-args     ["--allow-insecure-localhost" "--font-render-hinting=none"]})

(defn- default-create-browser
  [launch-args]
  (fn []
    (.launch pw/chromium #js {:args (clj->js launch-args)})))

(defn- ^:async create-instance
  [create-browser id-counter]
  (let [browser (await (create-browser))
        id      (swap! id-counter inc)]
    (l/info :origin "factory" :action "create" :browser-id id)
    (unchecked-set browser "__id" id)
    browser))

(defn- destroy-instance
  [browser]
  (l/info :origin "factory" :action "destroy"
          :browser-id (unchecked-get browser "__id"))
  (.close ^js browser))

(defn- validate-instance
  [browser]
  (l/info :origin "factory" :action "validate"
          :browser-id (unchecked-get browser "__id"))
  (.isConnected ^js browser))

(defn- make-factory
  [create-browser]
  (let [id-counter (atom 0)]
    #js {:create   (fn [] (create-instance create-browser id-counter))
         :destroy  destroy-instance
         :validate validate-instance}))

(defmethod system/init-key ::pool
  [_ cfg]
  (let [opts           (merge defaults (d/without-nils cfg))
        create-browser (or (::create-browser opts)
                           (default-create-browser (::launch-args opts)))]
    (l/info :hint "initializing browser pool" :max (::max opts))
    (gp/createPool (make-factory create-browser)
                   #js {:max                       (::max opts)
                        :min                       0
                        :testOnBorrow              true
                        :evictionRunIntervalMillis 5000
                        :numTestsPerEvictionRun    5
                        :acquireTimeoutMillis      (::acquire-timeout opts)
                        :idleTimeoutMillis         (::idle-timeout opts)})))

(defn- ^:async drain-pool
  [pool]
  (await (.drain ^js pool))
  (await (.clear ^js pool))
  nil)

(defmethod system/halt-key ::pool
  [_ pool]
  (when pool
    (l/info :hint "finalizing browser pool")
    (drain-pool pool)))

(defn- throw-browser-error
  [cause]
  (if (instance? TimeoutError cause)
    (ex/raise :type :internal
              :code :timeout
              :hint (ex-message cause)
              :cause cause)
    (throw cause)))

(defn ^:async exec
  "Check out a browser, run `handle` with a fresh page, and put the
  browser back. `handle` takes the page and may return a plain value or
  a promise. On failure the browser is destroyed instead of reused, and
  a Playwright timeout surfaces as an `:timeout` error."
  [pool context-opts handle]
  (when-not pool
    (throw (ex-info "browser pool is not started" {:reason ::not-started})))
  (let [browser (await (.acquire ^js pool))]
    (try
      (let [context (await (.newContext ^js browser context-opts))
            _       (l/trace :hint "exec:handle:start" :browser-id (unchecked-get browser "__id"))
            page    (await (.newPage ^js context))
            result  (await (handle page))]
        (await (.close ^js context))
        (l/trace :hint "exec:handle:end" :browser-id (unchecked-get browser "__id"))
        (await (.release ^js pool browser))
        result)
      (catch :default cause
        (try
          (await (.destroy ^js pool browser))
          (catch :default destroy-cause
            (l/warn :hint "browser destroy failed"
                    :cause (ex-message destroy-cause))))
        (throw-browser-error cause)))))
