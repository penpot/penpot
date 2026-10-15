;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-sync-wasm-text-test
  "With the WASM renderer, the size of a text that grows with its content
  is measured again after a sync writes it, as the SVG renderer does when
  it draws the text: a copy text with content of its own keeps its own
  size."
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.geom.shapes :as gsh]
   [app.common.logic.shapes :as cls]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.texts :as ctht]
   [app.common.types.modifiers :as ctm]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.wasm-text :as dwwt]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]))

(def ^:private orig-resize-wasm-text-all dwwt/resize-wasm-text-all)

(def ^:private resize-calls (atom []))

(defn- mock-resize-wasm-text-all
  ([ids] (mock-resize-wasm-text-all ids nil))
  ([ids opts]
   (swap! resize-calls conj {:ids (set ids) :opts opts})
   nil))

(t/use-fixtures :each
  {:before (fn []
             (cthi/reset-idmap!)
             (reset! resize-calls [])
             (set! dwwt/resize-wasm-text-all mock-resize-wasm-text-all))
   :after  (fn []
             (set! dwwt/resize-wasm-text-all orig-resize-wasm-text-all))})

(defn- resize
  "Resize the shape `label` of `file` to `width`, as a measure of its text
   would."
  [file label width]
  (let [page    (cthf/current-page file)
        changes (cls/generate-update-shapes
                 (pcb/empty-changes nil (:id page))
                 #{(cthi/id label)}
                 #(gsh/transform-shape % (ctm/change-size % width (:height %)))
                 (:objects page)
                 {:ignore-touched true})]
    (cthf/apply-changes file changes)))

(defn- setup-file
  "A component with a text and a copy whose text has content of its own;
   the main text then grows."
  []
  (-> (cthf/sample-file :file1)
      (ctho/add-frame-with-text :main-root :main-text "hello")
      (cthc/make-component :component1 :main-root)
      (cthc/instantiate-component :component1 :copy-root
                                  :children-labels [:copy-text])
      (ctht/set-text :copy-text [[["a longer text of its own"]]])
      (resize :main-text 200)))

(t/deftest a-sync-measures-again-the-copy-texts-it-writes
  (t/async
    done
    (let [file       (setup-file)
          store      (ths/setup-store file)
          undo-group (random-uuid)]
      (ths/run-store
       store done
       [(dwl/sync-file (:id file) (:id file) :components (cthi/id :component1) undo-group)]
       (fn [_]
         (t/is (= 1 (count @resize-calls)))
         (let [{:keys [ids opts]} (first @resize-calls)]
           (t/is (contains? ids (cthi/id :copy-text)))
           (t/is (:skip-component-sync? opts))
           (t/is (= undo-group (:undo-group opts)))))))))
