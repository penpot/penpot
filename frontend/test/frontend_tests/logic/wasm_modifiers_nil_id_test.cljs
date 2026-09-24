;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC Sucursal en España SL

(ns frontend-tests.logic.wasm-modifiers-nil-id-test
  "Reproduces the production crash \"Cannot read properties of null
   (reading '__u32_buffer')\".

   A modif-tree containing a nil shape id (production builds elide the
   asserts that catch this upstream, e.g. `update-dimensions` called
   with `[(:parent-id shape)]` when `shape` is missing) reached
   `wasm.api/propagate-modifiers` / `wasm.api/set-structure-modifiers`,
   and `mem.h32/write-uuid` crashed calling `uuid/get-u32` on nil while
   writing to the WASM heap.

   These tests assert that no nil id ever crosses the WASM boundary and
   that valid shapes in the same modif-tree are still processed."
  (:require
   [app.common.geom.rect :as grc]
   [app.common.math :as mth]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.modifiers :as ctm]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.modifiers :as dwm]
   [app.render-wasm.api :as wasm.api]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]))

(def ^:private captured-geometry-entries
  "Entries passed to `wasm.api/propagate-modifiers` during a test."
  (atom []))

(def ^:private captured-structure-entries
  "Entries passed to `wasm.api/set-structure-modifiers` during a test."
  (atom []))

(defn- install-capturing-spies!
  "Replace the plain WASM mocks with variants that record their input.
   Must run after `thw/setup-wasm-mocks!` so teardown still restores
   the real implementations."
  []
  (set! wasm.api/propagate-modifiers
        (fn [entries _pixel-precision]
          (swap! captured-geometry-entries into entries)
          (into []
                (map (fn [[id data]] [id (:transform data)]))
                entries)))
  (set! wasm.api/set-structure-modifiers
        (fn [entries]
          (swap! captured-structure-entries into entries)
          nil)))

(t/use-fixtures :each
  {:before (fn []
             (cthi/reset-idmap!)
             (reset! captured-geometry-entries [])
             (reset! captured-structure-entries [])
             (thw/setup-wasm-mocks!)
             (install-capturing-spies!))
   :after  (fn []
             (thw/teardown-wasm-mocks!))})

(t/deftest nil-id-does-not-reach-propagate-modifiers
  ;; A nil-keyed entry must be dropped before the WASM heap write while
  ;; the valid entry is still resized.
  (t/async
    done
    (let [file       (-> (cthf/sample-file :file1)
                         (ctho/add-rect :rect1 :x 10 :y 20 :width 100 :height 50))
          store      (ths/setup-store file)
          rect       (cths/get-shape file :rect1)
          resize     (ctm/change-dimensions-modifiers rect :width 200)
          modif-tree {nil {:modifiers resize}
                      (:id rect) {:modifiers resize}}
          events     [(dwm/apply-wasm-modifiers modif-tree {:ignore-snap-pixel true})]]
      (ths/run-store
       store done events
       (fn [new-state]
         (let [entry-ids (into #{} (map first) @captured-geometry-entries)
               file'     (ths/get-file-from-state new-state)
               rect'     (cths/get-shape file' :rect1)
               width     (-> rect' :points grc/points->rect :width)]
           (t/is (not (contains? entry-ids nil)))
           (t/is (contains? entry-ids (:id rect)))
           (t/is (mth/close? 200 width))))))))

(t/deftest nil-id-does-not-reach-set-structure-modifiers
  ;; A nil-keyed entry with structure modifiers must not produce
  ;; structure entries with a nil :parent or :id.
  (t/async
    done
    (let [file       (-> (cthf/sample-file :file1)
                         (ctho/add-rect :rect1 :x 10 :y 20 :width 100 :height 50))
          store      (ths/setup-store file)
          rect       (cths/get-shape file :rect1)
          modif-tree {nil {:modifiers (ctm/add-children nil [(uuid/next)] 0)}
                      (:id rect) {:modifiers (ctm/change-dimensions-modifiers rect :width 200)}}
          events     [(dwm/apply-wasm-modifiers modif-tree {:ignore-snap-pixel true})]]
      (ths/run-store
       store done events
       (fn [_new-state]
         (t/is (every? #(some? (:parent %)) @captured-structure-entries))
         (t/is (every? #(some? (:id %)) @captured-structure-entries)))))))
