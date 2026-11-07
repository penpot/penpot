;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.render-wasm.webgl-test
  "Unit tests for `app.render-wasm.api.webgl` context init and release."
  (:require
   [app.render-wasm.api.webgl :as webgl]
   [cljs.test :as t :include-macros true]))

(defn- fake-env
  "Builds `{canvas context module calls}` fakes.
  - `calls` records `:register`, `:delete`, `:clean` and `:lose` events.
  - Options:
    - `:throw-in` makes that module fn throw (`:_init`, `:_set_browser`, ...),
    - `:register-throws?` fails Emscripten registration (no handle exists),
    - `:nil-context?` makes `getContext` return nil (nothing is acquired)."
  [{:keys [throw-in register-throws? nil-context?]}]
  (let [calls   (atom [])
        lose    (fn [] (swap! calls conj [:lose]) nil)
        context #js {:getExtension (fn [name]
                                     (when (= name "WEBGL_lose_context")
                                       #js {:loseContext lose}))}
        gl      #js {:registerContext (fn [_ _]
                                        (swap! calls conj [:register])
                                        (when register-throws?
                                          (throw (js/Error. "register failed")))
                                        7)
                     :makeContextCurrent (fn [_] nil)
                     :deleteContext (fn [handle]
                                      (swap! calls conj [:delete handle])
                                      nil)}
        thrower (fn [name]
                  (fn [& _]
                    (when (= name throw-in)
                      (throw (js/Error. (str "fake " (pr-str name) " failure"))))
                    nil))
        module  #js {:GL gl
                     :_init (thrower :_init)
                     :_set_render_options (thrower :_set_render_options)
                     :_set_browser (thrower :_set_browser)
                     :_clean_up (fn [& _] (swap! calls conj [:clean]) nil)}
        canvas  #js {:getContext (fn [_ _] (when-not nil-context? context))}]
    {:calls calls :canvas canvas :context context :module module}))

(defn- init-opts
  [module]
  {:module module
   :context-id "webgl2"
   :css-width 100
   :css-height 100
   :dpr 1
   :flags 0
   :browser 0
   :params {}})

(defn- init-failure
  "Runs `init-context` against `env`, returning the thrown cause."
  [{:keys [canvas module]}]
  (try
    (webgl/init-context canvas (init-opts module))
    nil
    (catch :default cause
      cause)))

(defn- effect-count
  [calls effect]
  (count (filter #(= effect (first %)) calls)))

(t/deftest init-failure-releases-the-acquired-context
  (let [{:keys [canvas module calls]} (fake-env {:throw-in :_set_browser})
        failure (init-failure {:canvas canvas :module module})]
    (t/is (some? failure) "the original failure propagates")
    (t/is (= "_set_browser" (:fn (ex-data failure)))
          "the cause keeps the failing fn")
    (t/is (= 1 (effect-count @calls :clean)) "renderer state is released")
    (t/is (some #{[:delete 7]} @calls) "the registered handle is deleted")
    (t/is (some #{[:lose]} @calls) "the browser context is released")))

(t/deftest init-success-returns-context-and-handle
  (let [{:keys [canvas context module calls]} (fake-env {})
        result (webgl/init-context canvas (init-opts module))]
    (t/is (= 7 (:handle result)))
    (t/is (identical? context (:context result)))
    (t/is (zero? (effect-count @calls :clean)) "no release on success")
    (t/is (zero? (effect-count @calls :delete)) "no release on success")
    (t/is (zero? (effect-count @calls :lose)) "no release on success")))

(t/deftest nil-context-returns-nil-without-releasing
  (let [{:keys [canvas module calls]} (fake-env {:nil-context? true})]
    (t/is (nil? (webgl/init-context canvas (init-opts module))))
    (t/is (empty? @calls) "nothing acquired, nothing released")))

(t/deftest registration-failure-releases-browser-context-only
  (let [{:keys [canvas module calls]} (fake-env {:register-throws? true})
        failure (init-failure {:canvas canvas :module module})]
    (t/is (some? failure) "the original failure propagates")
    (t/is (zero? (effect-count @calls :clean))
          "no renderer state exists without registration")
    (t/is (zero? (effect-count @calls :delete))
          "no handle exists to delete")
    (t/is (= 1 (effect-count @calls :lose))
          "the browser context is still released")))
