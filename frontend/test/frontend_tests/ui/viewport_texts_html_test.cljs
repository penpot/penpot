;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.viewport-texts-html-test
  "How the SVG renderer measures text shapes and commits the result."
  (:require
   [app.common.geom.point :as gpt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.texts :as dwt]
   [app.main.store :as st]
   [app.main.ui.workspace.shapes.text.viewport-texts-html :as vth]
   [app.util.dom :as dom]
   [app.util.text-svg-position :as tsp]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [potok.v2.core :as ptk]
   [promesa.core :as p]))

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

(t/deftest override-copy-heal-phases-checks-overridden-auto-grow-copies
  (let [fixed     (text :fixed #{:content-group})
        untouched (text :auto-height nil)
        geometry  (text :auto-height #{:geometry-group})
        unknown   (text nil #{:content-group})
        override  (text :auto-width #{:content-group})]
    (t/is (= {(:id override) :check}
             (vth/override-copy-heal-phases (by-id fixed untouched geometry unknown override)))
          "only auto-grow texts with overridden content are checked")))

(t/deftest unchanged-copy-is-measured-in-the-check-phase
  (let [copy (text :auto-height #{:content-group})]
    (t/is (vth/text-changed? copy copy :check)
          "an unchanged copy waiting for its check is measured")
    (t/is (not (vth/text-changed? copy copy nil))
          "once checked, an unchanged copy is left alone")))

(defn- measure-with-box
  "Measure `shape` in heal `phase` with its layout box measured at `width` x
  `height`. Resolves to the events emitted to the store and the next heal
  phase. The resize and position-data requests are recorded as `::resize`
  and `::position-data` events carrying their options."
  [shape phase width height]
  (let [emitted (atom [])
        record  (fn [type] (fn [& args] (ptk/data-event type {:opts (last args)})))]
    (->> (mock/with-mocks*
           {tsp/calc-position-data   (mock/stub (constantly (p/resolved [fragment])))
            dom/query                (mock/stub (constantly #js {}))
            dom/get-bounding-rect    (mock/stub (constantly {:width width :height height}))
            dwt/resize-text          (mock/stub (record ::resize))
            dwt/update-position-data (mock/stub (record ::position-data))
            st/emit!                 (mock/stub (fn [& events] (swap! emitted into events) nil))}
           (await (measure-text shape #js {} phase)))
         (p/fmap (fn [next-phase] {:events @emitted :next next-phase})))))

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
    (t/is (nil? next)
          "nothing resizes it, so the heal ends")))

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
