;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-variant-switch-wasm-test
  "Regression test for #10588: switching a component's variant while
  render-wasm/v1 is active must not let the derived WASM text-resize
  commit trigger a component sync that discards the switch's merged
  overrides. See commit fb22c1547c for the same class of bug."
  (:require
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.wasm-text :as dwwt]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]))

(def ^:private orig-resize-wasm-text-all dwwt/resize-wasm-text-all)

(def ^:private resize-calls (atom []))

(defn- mock-resize-wasm-text-all
  ([ids] (mock-resize-wasm-text-all ids nil))
  ([ids opts]
   (swap! resize-calls conj {:ids ids :opts opts})
   nil))

(t/use-fixtures :each
  {:before (fn []
             (reset! resize-calls [])
             (set! dwwt/resize-wasm-text-all mock-resize-wasm-text-all))
   :after  (fn []
             (set! dwwt/resize-wasm-text-all orig-resize-wasm-text-all))})

(defn- setup-file
  []
  (-> (cthf/sample-file :file1)
      (ctho/add-frame :root-a)
      (cthc/make-component :comp-a :root-a)
      (ctho/add-frame-with-text :root-b :text-b "hello")
      (cthc/make-component :comp-b :root-b)
      (cthc/instantiate-component :comp-a :copy1)))

(t/deftest test-variant-switch-marks-wasm-resize-as-skip-component-sync
  (t/async
    done
    (let [file      (setup-file)
          store     (ths/setup-store file)
          copy1     (cths/get-shape file :copy1)
          comp-b-id (cthi/id :comp-b)

          events
          [(dwl/component-swap copy1 (:id file) comp-b-id true)]]

      (ths/run-store
       store done events
       (fn [_new-state]
         (t/is (= 1 (count @resize-calls))
               "resize-wasm-text-all should be called once for the swapped-in text")
         (let [{:keys [opts]} (first @resize-calls)]
           (t/is (:skip-component-sync? opts)
                 "the derived WASM resize commit must be tagged :skip-component-sync?, or watch-component-changes will treat it as a real edit and clobber the switch's merged overrides")))))))
