;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-sync-text-wasm-test
  "Regression test for #12721: after component sync with render-wasm/v1,
  auto-grow text copies must be scheduled for WASM remasure with
  :skip-component-sync? so box size heals without marking :geometry-group."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.wasm-text :as dwwt]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(def ^:private orig-resize-wasm-text-all dwwt/resize-wasm-text-all)

(def ^:private resize-calls (atom []))

(defn- mock-resize-wasm-text-all
  ([ids] (mock-resize-wasm-text-all ids nil))
  ([ids opts]
   (swap! resize-calls conj {:ids ids :opts opts})
   nil))

(t/use-fixtures :each
  {:before (fn []
             (cthi/reset-idmap!)
             (reset! resize-calls [])
             (set! dwwt/resize-wasm-text-all mock-resize-wasm-text-all))
   :after  (fn []
             (set! dwwt/resize-wasm-text-all orig-resize-wasm-text-all))})

(defn- setup-file
  [& {:keys [grow-type] :or {grow-type :auto-width}}]
  (-> (cthf/sample-file :file1)
      (ctho/add-frame-with-text :main-root :main-child "hello world"
                                :child-params {:grow-type grow-type})
      (cthc/make-component :component1 :main-root)
      (cthc/instantiate-component :component1 :copy-root
                                  {:children-labels [:copy-child]})))

(defn- bump-main-font-size
  [file]
  (let [page       (cthf/current-page file)
        main-child (cths/get-shape file :main-child)
        changes    (cls/generate-update-shapes
                    (pcb/empty-changes nil (:id page))
                    #{(:id main-child)}
                    (fn [shape]
                      (assoc-in shape
                                [:content :children 0 :children 0 :children 0 :font-size]
                                "32"))
                    (:objects page)
                    {})]
    (cthf/apply-changes file changes)))

(defn- collect-layout-updates
  "Subscribe before emit; accumulates deref'd `:layout/update` payloads."
  [store]
  (let [acc (atom [])]
    (->> (ptk/input-stream store)
         (rx/filter (ptk/type? :layout/update))
         (rx/tap #(swap! acc conj (deref %)))
         (rx/subs! (fn [_] nil)))
    acc))

(t/deftest test-sync-file-schedules-wasm-text-remasure
  (t/async
    done
    (let [file       (-> (setup-file) bump-main-font-size)
          store      (ths/setup-store file)
          page-id    (cthf/current-page-id file)
          copy-id    (:id (cths/get-shape file :copy-child))
          undo-group (uuid/next)
          layout-updates (collect-layout-updates store)

          events
          [(dwl/sync-file (:id file) (:id file)
                          :components (cthi/id :component1)
                          undo-group)]]

      (ths/run-store
       store done events
       (fn [_new-state]
         (t/is (= 1 (count @resize-calls))
               "resize-wasm-text-all should run once after sync")
         (let [{:keys [ids opts]} (first @resize-calls)
               layout-ids (->> @layout-updates
                               (filter #(= page-id (:page-id %)))
                               (mapcat :ids)
                               set)]
           (t/is (some #{copy-id} ids)
                 "remasure ids should include the copy text")
           (t/is (:skip-component-sync? opts)
                 "remasure must use :skip-component-sync? so size heal does not mark :geometry-group")
           (t/is (= undo-group (:undo-group opts)))
           (t/is (every? layout-ids ids)
                 (str "current-page :layout/update ids must include remasure ids; "
                      "layout=" layout-ids " remasure=" ids))))))))

(t/deftest test-sync-file-skips-remasure-for-fixed-text
  (t/async
    done
    (let [file   (-> (setup-file :grow-type :fixed) bump-main-font-size)
          store  (ths/setup-store file)

          events
          [(dwl/sync-file (:id file) (:id file)
                          :components (cthi/id :component1))]]

      (ths/run-store
       store done events
       (fn [_new-state]
         (t/is (empty? @resize-calls)
               "fixed-grow texts must not be scheduled for WASM remasure"))))))
