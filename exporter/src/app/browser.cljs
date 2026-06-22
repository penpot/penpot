;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns app.browser
  (:require
   ["generic-pool" :as gp]
   ["generic-pool/lib/errors.js" :as gpe]
   ["playwright" :as pw]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.config :as cf]
   [app.util.object :as obj]
   [promesa.core :as p]))

(l/set-level! :trace)

(def TimeoutError gpe/TimeoutError)

;; --- BROWSER API

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

(defn nav!
  ([page url] (nav! page url nil))
  ([page url {:keys [wait-until timeout] :or {wait-until "networkidle" timeout 20000}}]
   (.goto ^js page (str url) #js {:waitUntil wait-until :timeout timeout})))

(defn sleep
  [page ms]
  (.waitForTimeout ^js page ms))

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
   (-> (.waitForFunction ^js page
                         "async () => {
                            if (!document.fonts) return true;
                            try {
                              await Promise.all(Array.from(document.fonts, (face) => face.load()));
                            } catch (e) {}
                            await document.fonts.ready;
                            return document.fonts.status === 'loaded';
                          }"
                         nil
                         #js {:timeout timeout})
       (p/catch (fn [cause]
                  (l/warn :hint "wait-for-fonts timed out; continuing anyway"
                          :cause (ex-message cause))
                  (p/resolved nil))))))

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

(defn emulate-media!
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
(defn eval!
  [frame f]
  (.evaluate ^js frame f))

(defn select
  [frame selector]
  (.locator ^js frame selector))

(defn select-all
  [frame selector]
  (.$$ ^js frame selector))


;; --- BROWSER STATE

(defonce pool (atom nil))
(defonce pool-browser-id (atom 1))

(def browser-pool-factory
  (letfn [(create []
            (p/let [opts    #js {:args #js ["--allow-insecure-localhost" "--font-render-hinting=none"]}
                    browser (.launch pw/chromium opts)
                    id      (swap! pool-browser-id inc)]
              (l/info :origin "factory" :action "create" :browser-id id)
              (unchecked-set browser "__id" id)
              browser))

          (destroy [obj]
            (let [id (unchecked-get obj "__id")]
              (l/info :origin "factory" :action "destroy" :browser-id id)
              (.close ^js obj)))

          (validate [obj]
            (let [id (unchecked-get obj "__id")]
              (l/info :origin "factory" :action "validate" :browser-id id :obj obj)
              (p/resolved (.isConnected ^js obj))))]

    #js {:create create
         :destroy destroy
         :validate validate}))

(defn init
  []
  (let [opts #js {:max (cf/get :browser-pool-max 5)
                  :min (cf/get :browser-pool-min 0)
                  :testOnBorrow true
                  :evictionRunIntervalMillis 5000
                  :numTestsPerEvictionRun 5
                  ;; :acquireTimeoutMillis 120000 ; 2min
                  :acquireTimeoutMillis 10000 ; 10 s
                  :idleTimeoutMillis 10000}]

    (l/info :hint "initializing browser pool" :opts opts)
    (reset! pool (gp/createPool browser-pool-factory opts))
    (p/resolved nil)))

(defn stop
  []
  (when-let [pool (deref pool)]
    (l/info :hint "finalizing browser pool")
    (p/do!
     (.drain ^js pool)
     (.clear ^js pool))))

(defn- ex-ignore
  [p]
  (p/handle p (constantly nil)))

(defn- translate-browser-errors
  [cause]
  (if (instance? TimeoutError cause)
    (ex/raise :type :internal
              :code :timeout
              :hint (ex-message cause)
              :cause cause)
    (p/rejected cause)))

(defn exec!
  [config handle]
  (letfn [(handle-browser [browser]
            (p/let [id      (unchecked-get browser "__id")
                    context (.newContext ^js browser config)]
              (l/trace :hint "exec:handle:start" :browser-id id)
              (p/let [page   (.newPage ^js context)
                      result (handle page)]
                (.close ^js context)
                (l/trace :hint "exec:handle:end" :browser-id id)
                result)))

          (on-acquire [pool browser]
            (-> (handle-browser browser)
                (p/then (fn [result]
                          (.release ^js pool browser)
                          result))
                (p/catch (fn [cause]
                           (p/do!
                            (ex-ignore (.destroy ^js pool browser))
                            (p/rejected cause))))))]

    (when-let [pool (deref pool)]
      (-> (p/do! (.acquire ^js pool))
          (p/then (partial on-acquire pool))
          (p/catch translate-browser-errors)))))
