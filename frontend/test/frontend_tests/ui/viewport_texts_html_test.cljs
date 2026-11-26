;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.viewport-texts-html-test
  "How the SVG renderer measures text shapes and commits the result."
  (:require
   ["jsdom" :refer [JSDOM]]
   ["react" :as react]
   ["react-dom" :as rd]
   ["react-dom/client" :as rdc]
   [app.common.geom.point :as gpt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.reflow :as wrf]
   [app.main.data.workspace.texts :as dwt]
   [app.main.store :as st]
   [app.main.ui.shapes.text.html-text :as html]
   [app.main.ui.workspace.shapes.text.viewport-texts-html :as vth]
   [app.util.dom :as dom]
   [app.util.text-svg-position :as tsp]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]
   [promesa.core :as p]
   [rumext.v2 :as mf]))

(def ^:private fixed-text
  {:id (uuid/next) :type :text :grow-type :fixed})

(defn- measure-text
  "Measure `shape` as the SVG renderer does and return the measurement
  promise. Kept out of the `^:async` test bodies: there, a call through the
  var compiles to an awaited wrapper that would wait for the measurement."
  ([shape]
   (measure-text shape nil nil))
  ([shape node phase]
   (#'vth/update-text-shape shape node phase)))

(defn- with-measurement
  "Run `f` with the text measurement mocked to `measurement`, a promise the
  test settles. Events emitted to the store are recorded in `emitted`."
  [measurement emitted f]
  (mock/with-mocks*
    {tsp/calc-position-data (mock/stub (constantly measurement))
     st/emit!               (mock/stub (fn [& events] (swap! emitted into events) nil))}
    (await (f))))

(t/deftest ^:async measurement-promise-settles-with-the-measurement
  (let [measurement (p/deferred)
        emitted     (atom [])
        settled?    (atom false)]
    (await
     (with-measurement
       measurement
       emitted
       (^:async fn []
         (let [result (p/finally (measure-text fixed-text)
                                 #(reset! settled? true))]
           (await (async/settle))
           (t/is (false? @settled?)
                 "the promise stays pending while the text is measured")
           (p/resolve measurement [])
           (await result)
           (t/is (true? @settled?)
                 "the promise settles once the measurement settles")
           (t/is (some #(= ::dwt/clean-text-modifier (ptk/type %)) @emitted)
                 "the measurement completes and cleans the text modifier")))))))

(t/deftest ^:async failed-measurement-resolves-to-nil
  (let [measurement (p/deferred)]
    (await
     (with-measurement
       measurement
       (atom [])
       (^:async fn []
         (let [result (measure-text fixed-text)]
           (p/reject measurement (ex-info "measure failed" {}))
           (t/is (nil? (await result))
                 "a failed measurement resolves to nil")))))))

(def ^:private fragment
  "A position-data fragment inside the box of `text`."
  {:x 0 :y 17 :width 100 :height 17 :text "label"})

(defn- text
  [grow-type touched]
  {:id (uuid/next) :type :text :grow-type grow-type :touched touched
   :x 0 :y 0 :width 200 :height 17
   :points [(gpt/point 0 0) (gpt/point 200 0) (gpt/point 200 17) (gpt/point 0 17)]
   :position-data [fragment]})

(defn- by-id
  [& shapes]
  (into {} (map (juxt :id identity)) shapes))

(defn- in-copy
  [shape]
  (assoc shape :shape-ref (uuid/next)))

(t/deftest copy-heal-phases-checks-auto-grow-texts-in-copies
  (let [fixed     (in-copy (text :fixed #{:content-group}))
        unknown   (in-copy (text nil #{:content-group}))
        main      (text :auto-height #{:content-group})
        untouched (in-copy (text :auto-height nil))
        geometry  (in-copy (text :auto-height #{:geometry-group}))
        override  (in-copy (text :auto-width #{:content-group}))]
    (t/is (= {(:id untouched) :check
              (:id geometry)  :check
              (:id override)  :check}
             (vth/copy-heal-phases (by-id fixed unknown main untouched geometry override)))
          (str "every auto-grow text in a copy is checked, also one nested in a "
               "main that overrides it, which has no touched flag of its own; "
               "texts of a main and fixed texts are not"))))

(t/deftest unchanged-copy-is-measured-in-the-check-phase
  (let [copy (text :auto-height #{:content-group})]
    (t/is (vth/text-changed? copy copy :check)
          "an unchanged copy waiting for its check is measured")
    (t/is (not (vth/text-changed? copy copy nil))
          "once checked, an unchanged copy is left alone")
    (t/is (not (vth/text-changed? copy copy :settle))
          "a copy waiting to settle is measured only once its heal resize changes it")))

(defn- measure-with-box
  "Measure `shape` in heal `phase` with its layout box measured at `width` x
  `height`. Resolves to the events emitted to the store and the next heal
  phase. The resize and position-data requests are recorded as `::resize`
  and `::position-data` events carrying their options."
  ([shape phase width height]
   (measure-with-box shape phase width height [fragment]))
  ([shape phase width height position-data]
   (let [emitted (atom [])
         record  (fn [type] (fn [& args] (ptk/data-event type {:opts (last args)})))]
     (->> (mock/with-mocks*
            {tsp/calc-position-data   (mock/stub (constantly (p/resolved position-data)))
             dom/query                (mock/stub (constantly #js {}))
             dom/get-bounding-rect    (mock/stub (constantly {:width width :height height}))
             dwt/resize-text          (mock/stub (record ::resize))
             dwt/update-position-data (mock/stub (record ::position-data))
             st/emit!                 (mock/stub (fn [& events] (swap! emitted into events) nil))}
            (await (measure-text shape #js {} phase)))
          (p/fmap (fn [next-phase] {:events @emitted :next next-phase}))))))

(defn- committed
  "Options of the `type` request among `events`, `::none` if there is none."
  [type events]
  (or (some #(when (= type (ptk/type %)) (or (:opts (deref %)) {})) events)
      ::none))

(def ^:private no-undo {:save-undo? false})

(t/deftest ^:async check-phase-heals-a-drifted-copy-without-undo
  (let [{:keys [events next]}
        (await (measure-with-box (text :auto-height #{:content-group}) :check 200 34))]
    (t/is (= no-undo (committed ::resize events))
          "a copy measured taller than its stored box is resized, with no undo step")
    (t/is (= no-undo (committed ::position-data events))
          "its position-data is written with the new layout, with no undo step")
    (t/is (= :settle next)
          "the measurement caused by the resize belongs to the heal")))

(t/deftest ^:async check-phase-leaves-a-matching-copy-alone
  (let [{:keys [events next]}
        (await (measure-with-box (text :auto-height #{:content-group}) :check 200 17.4))]
    (t/is (= ::none (committed ::resize events))
          "a copy that matches its stored box is not resized")
    (t/is (= ::none (committed ::position-data events))
          "nor is its position-data written back")
    (t/is (some #(= ::dwt/clean-text-modifier (ptk/type %)) events)
          "the measurement still completes")
    (t/is (nil? next)
          "the heal ends")))

(t/deftest ^:async check-phase-heals-a-copy-without-position-data
  (let [copy (assoc (text :auto-height #{:content-group}) :position-data nil)
        {:keys [events next]} (await (measure-with-box copy :check 200 17.4))]
    (t/is (= no-undo (committed ::position-data events))
          "a copy that lost its position-data in a sync gets it back, with no undo step")
    (t/is (= ::none (committed ::resize events))
          "a copy whose size did not drift is not resized")
    (t/is (nil? next)
          "nothing resizes it, so the heal ends")))

(t/deftest ^:async check-phase-heals-a-drifted-copy-without-position-data
  (let [copy (assoc (text :auto-height #{:content-group}) :position-data nil)
        {:keys [events next]} (await (measure-with-box copy :check 200 34))]
    (t/is (= no-undo (committed ::resize events))
          "a drifted copy without position-data is resized, with no undo step")
    (t/is (= no-undo (committed ::position-data events))
          "and gets its position-data back, with no undo step")
    (t/is (= :settle next)
          "the measurement caused by the resize belongs to the heal")))

(t/deftest ^:async check-phase-commits-nothing-when-it-cannot-measure
  (let [{:keys [events]}
        (await (measure-with-box (text :auto-height #{:content-group}) :check 0 0))]
    (t/is (= ::none (committed ::resize events))
          "an empty layout box is not a size")
    (t/is (= ::none (committed ::position-data events))
          "nothing is written for a text that could not be measured")))

(t/deftest ^:async settle-phase-commits-without-undo
  (let [{:keys [events next]}
        (await (measure-with-box (text :auto-height #{:content-group}) :settle 200 34))]
    (t/is (= no-undo (committed ::resize events))
          "the measurement after a heal resize records no undo step")
    (t/is (= no-undo (committed ::position-data events))
          "nor does its position-data")
    (t/is (nil? next)
          "the heal ends")))

(t/deftest ^:async no-phase-commits-as-usual
  (let [{:keys [events next]}
        (await (measure-with-box (text :auto-height nil) nil 200 17.4))]
    (t/is (= {} (committed ::resize events))
          "outside a heal, a size within 1px is still committed, with undo")
    (t/is (= {} (committed ::position-data events))
          "and so is the position-data")
    (t/is (nil? next))))

(def ^:private fallback-font-position-data
  "Position-data measured with a font that failed to load."
  (with-meta [fragment] {::tsp/font-failed true}))

(t/deftest font-failed-reads-the-measurement-mark
  (t/is (tsp/font-failed? fallback-font-position-data))
  (t/is (not (tsp/font-failed? [fragment]))))

(t/deftest ^:async check-phase-writes-no-size-from-a-fallback-font
  (let [{:keys [events next]}
        (await (measure-with-box (text :auto-height #{:content-group}) :check 200 34
                                 fallback-font-position-data))]
    (t/is (= ::none (committed ::resize events))
          "a size laid out with a fallback font is not written on page open")
    (t/is (= ::none (committed ::position-data events))
          "nor its position-data")
    (t/is (nil? next)
          "the heal ends")))

(t/deftest ^:async check-phase-restores-position-data-with-a-fallback-font
  (let [copy (assoc (text :auto-height #{:content-group}) :position-data nil)
        {:keys [events]}
        (await (measure-with-box copy :check 200 34 fallback-font-position-data))]
    (t/is (= no-undo (committed ::position-data events))
          "a copy without position-data still gets it, with no undo step")
    (t/is (= ::none (committed ::resize events))
          "but its size is not written")))

(t/deftest ^:async no-phase-commits-a-fallback-font-size-as-usual
  (let [{:keys [events]}
        (await (measure-with-box (text :auto-height nil) nil 200 34
                                 fallback-font-position-data))]
    (t/is (= {} (committed ::resize events))
          "outside a heal, the measured size is committed as before")))

;; The heal phases through the mounted renderer: a text is measured when it
;; mounts, and how often it mounts depends on its phase.

(def ^:private measured-text
  "Stands in for `html/text-shape*`: a node to measure, with no content."
  (react/forwardRef
   (fn [_props ref]
     (react/createElement "div" #js {:ref ref :className "measured-text"}))))

(defn- next-immediate
  "Resolves after the tasks React's scheduler queued with `setImmediate`
  so far have run."
  []
  (js/Promise. (fn [resolve] (js/setImmediate resolve))))

(defn- mount-text-changes
  "Mount `text-changes-renderer` in a jsdom document. Returns `:render`,
  which renders it with a map of text shapes, `:measuring`, the number of
  texts mounted to be measured, `:errors`, what React reported, and
  `:unmount`, which resolves once React's scheduler has run. react-dom
  reads the global `window`, so the jsdom one stays installed until then."
  []
  (let [errors    (atom [])
        on-error  (fn [error _info] (swap! errors conj error))
        window    (.-window (JSDOM. "<!doctype html><div id=\"root\"></div>"))
        container (.getElementById (.-document window) "root")
        root      (rdc/createRoot container #js {:onUncaughtError on-error
                                                 :onCaughtError on-error
                                                 :onRecoverableError on-error})
        previous  (.-window js/globalThis)]
    (set! (.-window js/globalThis) window)
    {:render    (fn [shapes]
                  (rd/flushSync
                   #(.render root (mf/element vth/text-changes-renderer
                                              #js {"text-shapes" shapes}))))
     :measuring #(.-length (.querySelectorAll container ".measured-text"))
     :errors    #(deref errors)
     :unmount   (fn []
                  (rd/flushSync #(.unmount root))
                  (p/finally (next-immediate)
                             #(set! (.-window js/globalThis) previous)))}))

(defn- with-text-changes
  "Run `(f mount counts)` with `text-changes-renderer` mounted and every
  text measured `height` px tall. `counts` returns the number of
  measurements so far and the options of each resize request. Resolves
  once `f` settles and the renderer is unmounted."
  [height f]
  (let [measures (atom 0)
        resizes  (atom [])]
    (mock/with-mocks*
      {tsp/calc-position-data   (mock/stub (fn [_id]
                                             (swap! measures inc)
                                             (p/resolved [fragment])))
       dom/query                (mock/stub (constantly #js {}))
       dom/get-bounding-rect    (mock/stub (constantly {:width 200 :height height}))
       dwt/resize-text          (mock/stub (fn [& args]
                                             (swap! resizes conj (last args))
                                             (ptk/data-event ::resize {})))
       dwt/update-position-data (mock/stub (fn [& _] (ptk/data-event ::position-data {})))
       wrf/run-pending!         (mock/stub (fn [_kind _ids f] (f)))
       html/text-shape*         measured-text
       st/emit!                 (mock/stub (constantly nil))}
      (let [mount  (mount-text-changes)
            counts (fn [] {:measures @measures :resizes @resizes})]
        (await (f mount counts))
        (await ((:unmount mount)))))))

(t/deftest ^:async drifted-copy-is-measured-once-to-check-and-once-to-settle
  (let [copy    (in-copy (text :auto-height nil))
        resized (assoc copy :height 34)]
    (await
     (with-text-changes
       34
       (^:async fn [{:keys [render measuring errors]} counts]
         (render (by-id copy))
         (await (async/wait-for #(zero? (measuring)) "the check measurement ends"))
         (t/is (= {:measures 1 :resizes [no-undo]} (counts))
               "the check measures the copy once and resizes it, with no undo step")

         (render (by-id resized))
         (await (async/wait-for #(zero? (measuring)) "the settle measurement ends"))
         (t/is (= {:measures 2 :resizes [no-undo no-undo]} (counts))
               "the heal resize measures it once more, with no undo step")

         (render (by-id resized))
         (await (async/settle))
         (t/is (= 2 (:measures (counts)))
               "once settled, the copy is not measured again")
         (t/is (empty? (errors))))))))

(t/deftest ^:async matching-copy-is-measured-once
  (let [copy (in-copy (text :auto-height nil))]
    (await
     (with-text-changes
       17.4
       (^:async fn [{:keys [render measuring errors]} counts]
         (render (by-id copy))
         (await (async/wait-for #(zero? (measuring)) "the check measurement ends"))
         (t/is (= {:measures 1 :resizes []} (counts))
               "the check measures the copy once and leaves its size alone")

         (render (by-id copy))
         (await (async/settle))
         (t/is (= 1 (:measures (counts)))
               "once checked, the copy is not measured again")
         (t/is (empty? (errors))))))))
