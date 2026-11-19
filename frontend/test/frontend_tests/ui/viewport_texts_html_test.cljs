;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.ui.viewport-texts-html-test
  "How the SVG renderer measures text shapes and commits the result."
  (:require
   [app.common.uuid :as uuid]
   [app.main.data.workspace.texts :as dwt]
   [app.main.store :as st]
   [app.main.ui.workspace.shapes.text.viewport-texts-html :as vth]
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
  [shape]
  (#'vth/update-text-shape shape nil))

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
