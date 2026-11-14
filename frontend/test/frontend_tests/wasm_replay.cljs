;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.wasm-replay
  "Replays generated component programs through the frontend in a browser,
   with the WASM render engine laying the shapes out. The page loads the
   engine on a canvas and fetches the programs from `programs-uri`; each
   program replays as the frontend generated tests replay it, and the
   results go to the console and to `globalThis.wasmReplay`."
  (:require
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.main.data.helpers :as dsh]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]
   [cljs.reader :as reader]
   [cljs.test :as t]
   [frontend-tests.composable-tests.generated-test :as gt]
   [frontend-tests.composable-tests.interpreter :as ftm]
   [promesa.core :as p]))

(defn- programs-uri
  "The programs file the page names in its `programs` query parameter: EDN
   in a `.js` file, the only kind the development server serves there."
  []
  (let [params (js/URLSearchParams. (.. js/globalThis -location -search))]
    (str "/js/test-wasm/" (or (.get params "programs") "programs.js"))))

(def ^:private plain-install! ftm/install!)

(defn- load-objects!
  "Load the shapes of the current page of `state` into the WASM engine when
   they differ from those of `old`."
  [_ _ old state]
  (let [objects (dsh/lookup-page-objects state)]
    (when (and (some? objects) (not (identical? objects (dsh/lookup-page-objects old))))
      (wasm.api/set-objects objects))))

(defn- wasm-install!
  "Install `situation` as `ftm/install!` does, with the WASM engine on and
   holding the shapes of the current page, which it loads again whenever
   they change: the shapes stay plain maps, which the engine does not
   follow by themselves."
  [situation]
  (plain-install! situation)
  (st/emit! #(update % :features (fnil conj #{}) "render-wasm/v1"))
  (wasm.api/set-objects (dsh/lookup-page-objects @st/state))
  (add-watch st/state ::wasm-objects load-objects!)
  situation)

(def ^:private plain-op->events ftm/op->events)

(defn- svg-text-events
  "The events of `op` as `ftm/op->events` gives them, but a text edit runs
   with the WASM engine off: the WASM text editor commits through its own
   editing session, which the headless page does not have, so the edit goes
   through the SVG editor's commit and the engine lays out what follows."
  [op situation]
  (let [events (plain-op->events op situation)]
    (if (instance? n/EditText op)
      (-> [#(update % :features disj "render-wasm/v1")]
          (into events)
          (conj #(update % :features (fnil conj #{}) "render-wasm/v1")))
      events)))

(def ^:private wasm-layouts
  "How many times the WASM engine propagated modifiers."
  (atom 0))

(def ^:private plain-propagate wasm.api/propagate-modifiers)

(defn- counted-propagate
  [entries snap-pixel? snap-ignore-axis]
  (swap! wasm-layouts inc)
  (plain-propagate entries snap-pixel? snap-ignore-axis))

(def ^:private plain-step! ftm/run-generated-step)

(defn- logged-step!
  "Run a step as `ftm/run-generated-step` does, logging it."
  [live op]
  (js/console.log "WASM-REPLAY step" (pr-str (type op)))
  (plain-step! live op))

(defn- ^:async replay
  "Replay `program`, returning its failures and errors."
  [program]
  (t/set-env! (t/empty-env))
  ((:before ftm/fixture))
  (try
    (await (gt/replay-program program))
    (catch :default cause
      (js/console.log "WASM-REPLAY error" (ex-message cause) (some-> (.-stack cause) (subs 0 1500)))
      (t/do-report {:type :error :message (ex-message cause) :actual cause}))
    (finally
      (remove-watch st/state ::wasm-objects)
      ((:after ftm/fixture))))
  (let [counters (:report-counters (t/get-current-env))]
    (select-keys counters [:pass :fail :error])))

(defn- ^:async run-programs
  [programs]
  (loop [index 0 results []]
    (if (< index (count programs))
      (let [program (nth programs index)
            result  (await (replay program))]
        (js/console.log "WASM-REPLAY" index (pr-str result) (pr-str program))
        (set! (.-wasmReplayProgress js/globalThis) (inc index))
        (recur (inc index) (conj results (assoc result :index index))))
      (let [failed (filterv #(or (pos? (:fail % 0)) (pos? (:error % 0))) results)]
        (js/console.log "WASM-REPLAY done" (count results) "programs," (count failed) "failed"
                        (pr-str (mapv :index failed)) "WASM layouts" @wasm-layouts)
        (set! (.-wasmReplay js/globalThis)
              (clj->js {:done true :total (count results) :failed (mapv :index failed)
                        :layouts @wasm-layouts}))
        results))))

(defn ^:export init
  "Load the WASM engine on a canvas of the page, then replay the programs."
  []
  (enable-console-print!)
  (set! ftm/install! wasm-install!)
  (set! ftm/run-generated-step logged-step!)
  (set! ftm/op->events svg-text-events)
  (set! wasm.api/propagate-modifiers counted-propagate)
  (.addEventListener js/globalThis "unhandledrejection"
                     #(js/console.log "WASM-REPLAY unhandled" (str (.-reason %))))
  (p/let [_      @wasm.api/module
          canvas (js/document.createElement "canvas")
          _      (set! (.-width canvas) 800)
          _      (set! (.-height canvas) 600)
          _      (.appendChild js/document.body canvas)
          ready  (wasm.api/init-canvas-context canvas)
          resp   (js/fetch (programs-uri))
          text   (.text resp)]
    (js/console.log "WASM-REPLAY engine" (if ready "ready" "not ready"))
    (run-programs (reader/read-string text))))
