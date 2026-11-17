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
   results go to the console and to `globalThis.wasmReplay`.

   Query parameters: `programs` names the file or `edn` holds the programs,
   `start` the first index, `engine=plain` replays with the WASM engine off,
   `debug` logs the targets, the WASM layouts and the shapes each program
   ends with, and `watch` logs the events and the moves of the shapes the
   operations target."
  (:require
   [app.common.test-helpers.composable.comp.nodes :as n]
   [app.common.test-helpers.composable.core :as tm]
   [app.common.types.text :as txt]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.repo :as rp]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]
   [beicon.v2.core :as rx]
   [cljs.reader :as reader]
   [cljs.test :as t]
   [frontend-tests.composable-tests.generated-test :as gt]
   [frontend-tests.composable-tests.interpreter :as ftm]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

(defn- query-param
  [name]
  (.get (js/URLSearchParams. (.. js/globalThis -location -search)) name))

(defn- programs-uri
  "The programs file the page names in its `programs` query parameter: EDN
   in a `.js` file, the only kind the development server serves there."
  []
  (str "/js/test-wasm/" (or (query-param "programs") "programs.js")))

(defn- plain-engine?
  "Whether the page names the `plain` engine, replaying the programs with
   the WASM engine off, as the frontend generated tests replay them."
  []
  (= "plain" (query-param "engine")))

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

(defn- svg-install!
  "Install `situation` as `ftm/install!` does, with the WASM engine off even
   when the page's configuration turns it on."
  [situation]
  (plain-install! situation)
  (st/emit! #(update % :features disj "render-wasm/v1"))
  situation)

(defn- kept-text-size
  "An event in place of the WASM engine's text resizes. The engine measures
   the texts it lays out, but the model keeps a text's stored size, and the
   generated texts are too narrow to measure: a measured copy text would
   differ from its unmeasured main. It takes the arities of the events it
   stands for, which callers invoke directly."
  ([id] (kept-text-size id nil))
  ([_ _] (ptk/reify ::kept-text-size)))

(def ^:private watched
  "The ids of the shapes the replayed operations target."
  (atom #{}))

(def ^:private plain-op->events ftm/op->events)

(defn- replay-events
  "The events of `op` as `ftm/op->events` gives them, logging its target in
   debug. With the WASM engine on, a text edit runs with the engine off: the
   WASM text editor commits through its own editing session, which the
   headless page does not have, so the edit goes through the SVG editor's
   commit and the engine lays out what follows."
  [op situation]
  (when (and (query-param "watch") (some? (:target op)))
    (when-let [id (try (tm/target-shape-id situation (:target op)) (catch :default _ nil))]
      (swap! watched conj id)))
  (when (and (query-param "debug") (some? (:target op)))
    (js/console.log "WASM-REPLAY target" (pr-str (type op))
                    (pr-str (try (tm/target-shape-id situation (:target op)) (catch :default _ nil)))))
  (when (and (query-param "debug") (instance? n/EditText op))
    (let [shape (tm/shape-by-id situation (tm/target-shape-id situation (:target op)))]
      (js/console.log "WASM-REPLAY edit" (pr-str (:edit op)) (pr-str (:value op))
                      (pr-str (some->> (:content (n/text-edit-commit shape (:edit op) (:value op)))
                                       txt/node-seq (keep :font-weight) distinct)))))
  (let [events (plain-op->events op situation)]
    (if (and (instance? n/EditText op) (not (plain-engine?)))
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
  (let [result (plain-propagate entries snap-pixel? snap-ignore-axis)]
    (when (query-param "debug")
      (js/console.log "WASM-REPLAY propagate" (pr-str entries) "=>" (pr-str result)))
    result))

(def ^:private plain-step! ftm/run-generated-step)

(defn- logged-step!
  "Run a step as `ftm/run-generated-step` does, logging it."
  [live op]
  (js/console.log "WASM-REPLAY step" (pr-str (type op)))
  (plain-step! live op))

(defn- log-shapes!
  "Log the shapes of the current page at the end of a program when the page
   names the `debug` query parameter: name, type, id, parent name and id, rectangle,
   transform, touched groups, shape ref, layout, and a text's characters and
   font weights."
  []
  (when (query-param "debug")
    (let [objects (dsh/lookup-page-objects @st/state)]
      (js/console.log "WASM-REPLAY shapes" (count objects))
      (doseq [shape (sort-by :name (vals objects))]
        (js/console.log "WASM-REPLAY shape"
                        (pr-str [(:name shape) (:type shape) (:id shape)
                                 (:name (get objects (:parent-id shape))) (:parent-id shape)
                                 (some-> (:selrect shape) (select-keys [:x :y :width :height]))
                                 (:transform shape) (:touched shape)
                                 (:shape-ref shape) (:layout shape)
                                 (when (= :text (:type shape))
                                   (some-> (:content shape) txt/content->text))
                                 (when (= :text (:type shape))
                                   (some->> (:content shape) txt/node-seq (keep :font-weight) distinct))]))))))

(defn- ^:async replay
  "Replay `program` with the backend and timers mocked, as the frontend
   generated tests replay it, returning its failures and errors."
  [program]
  (t/set-env! (t/empty-env))
  ((:before ftm/fixture))
  (try
    (await (mock/with-mocks* {rp/cmd! mock/rpc-cmd-mock rx/timer mock/timer-mock}
             (try
               (await (gt/replay-program program))
               (catch :default cause
                 (js/console.log "WASM-REPLAY error" (ex-message cause)
                                 (some-> (.-stack cause) (subs 0 1500)))
                 (t/do-report {:type :error :message (ex-message cause) :actual cause})))))
    (finally
      (log-shapes!)
      (remove-watch st/state ::wasm-objects)
      ((:after ftm/fixture))))
  (let [counters (:report-counters (t/get-current-env))]
    (select-keys counters [:pass :fail :error])))

(defn- ^:async run-programs
  "Replay `programs` from the index the page names in its `start` query
   parameter, or from the first."
  [programs]
  (loop [index (or (some-> (query-param "start") js/parseInt) 0) results []]
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

(defn- watch-shape!
  "Log the events the store handles and each change of the place of the
   shapes the replayed operations target."
  []
  (rx/sub! st/stream #(js/console.log "WASM-REPLAY event" (str (ptk/type %))))
  (add-watch st/state ::watch-shape
             (fn [_ _ old state]
               (let [objects  (dsh/lookup-page-objects state)
                     previous (dsh/lookup-page-objects old)]
                 (when-not (identical? objects previous)
                   (doseq [[id shape] objects
                           :when (contains? @watched id)
                           :let [before (get previous id)]
                           :when (not= (:selrect shape) (:selrect before))]
                     (js/console.log "WASM-REPLAY watch" (:name shape)
                                     (pr-str (select-keys (:selrect before) [:x :y :width :height]))
                                     "->" (pr-str (select-keys (:selrect shape) [:x :y :width :height]))
                                     (pr-str (:touched shape)))))))))

(defn ^:export init
  "Load the WASM engine on a canvas of the page, then replay the programs."
  []
  (enable-console-print!)
  (set! ftm/run-generated-step logged-step!)
  (set! ftm/op->events replay-events)
  (if (plain-engine?)
    (set! ftm/install! svg-install!)
    (do (set! ftm/install! wasm-install!)
        (set! wasm.api/propagate-modifiers counted-propagate)
        (set! dwwt/resize-wasm-text kept-text-size)
        (set! dwwt/resize-wasm-text-debounce kept-text-size)
        (set! dwwt/resize-wasm-text-all kept-text-size)))
  (when (query-param "watch")
    (watch-shape!))
  (.addEventListener js/globalThis "unhandledrejection"
                     #(js/console.log "WASM-REPLAY unhandled" (str (.-reason %))))
  (p/let [_      @wasm.api/module
          canvas (js/document.createElement "canvas")
          _      (set! (.-width canvas) 800)
          _      (set! (.-height canvas) 600)
          _      (.appendChild js/document.body canvas)
          ready  (wasm.api/init-canvas-context canvas)
          text   (or (query-param "edn")
                     (p/let [resp (js/fetch (programs-uri))] (.text resp)))]
    (js/console.log "WASM-REPLAY engine" (if ready "ready" "not ready")
                    (if (plain-engine?) "(off)" "(on)"))
    (run-programs (reader/read-string text))))
