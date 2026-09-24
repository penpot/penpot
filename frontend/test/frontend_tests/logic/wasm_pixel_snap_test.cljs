;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.logic.wasm-pixel-snap-test
  "Covers which pixel-grid snapping options reach the WASM renderer.

   The rounding happens in Rust, so these tests assert on the arguments
   crossing the bridge: which axis an axis-locked drag leaves alone, and
   that rotation does not snap."
  (:require
   [app.common.geom.point :as gpt]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.types.modifiers :as ctm]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.transforms :as dwt]
   [app.render-wasm.api :as wasm.api]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]))

(def ^:private captured-snap-options
  "One entry per `wasm.api/propagate-modifiers` call during a test."
  (atom []))

(defn- install-capturing-spy!
  "Records the snap options of every propagation. Must run after
   `thw/setup-wasm-mocks!` so teardown restores the real implementation."
  []
  (set! wasm.api/propagate-modifiers
        (fn [entries snap-pixel? snap-ignore-axis]
          (swap! captured-snap-options conj
                 {:snap-pixel? snap-pixel? :snap-ignore-axis snap-ignore-axis})
          (into []
                (map (fn [[id data]] [id (:transform data)]))
                entries))))

(defn- enable-pixel-grid
  []
  (fn [state]
    (update state :workspace-layout conj :snap-pixel-grid)))

(t/use-fixtures :each
  {:before (fn []
             (cthi/reset-idmap!)
             (reset! captured-snap-options [])
             (thw/setup-wasm-mocks!)
             (install-capturing-spy!))
   :after  (fn []
             (thw/teardown-wasm-mocks!))})

(t/deftest axis-locked-move-tells-the-renderer-which-axis-to-ignore
  (t/async
    done
    (let [file       (-> (cthf/sample-file :file1)
                         (ctho/add-rect :rect1 :x 10.4 :y 20.6 :width 100.5 :height 50.3))
          store      (ths/setup-store file)
          rect       (cths/get-shape file :rect1)
          modif-tree (dwm/create-modif-tree [(:id rect)]
                                            (ctm/move-modifiers (gpt/point 5.2 0)))
          events     [(enable-pixel-grid)
                      (dwm/apply-wasm-modifiers modif-tree :snap-ignore-axis :y)]]
      (ths/run-store
       store done events
       (fn [_new-state]
         (t/is (= [{:snap-pixel? true :snap-ignore-axis :y}]
                  @captured-snap-options)))))))

(t/deftest move-without-axis-lock-snaps-both-axes
  (t/async
    done
    (let [file       (-> (cthf/sample-file :file1)
                         (ctho/add-rect :rect1 :x 10.4 :y 20.6 :width 100.5 :height 50.3))
          store      (ths/setup-store file)
          rect       (cths/get-shape file :rect1)
          modif-tree (dwm/create-modif-tree [(:id rect)]
                                            (ctm/move-modifiers (gpt/point 5.2 3.7)))
          events     [(enable-pixel-grid)
                      (dwm/apply-wasm-modifiers modif-tree)]]
      (ths/run-store
       store done events
       (fn [_new-state]
         (t/is (= [{:snap-pixel? true :snap-ignore-axis nil}]
                  @captured-snap-options)))))))

(t/deftest rotation-does-not-snap-to-the-pixel-grid
  (t/async
    done
    (let [file   (-> (cthf/sample-file :file1)
                     (ctho/add-rect :rect1 :x 10.4 :y 20.6 :width 100.5 :height 50.3))
          store  (ths/setup-store file)
          rect   (cths/get-shape file :rect1)
          events [(enable-pixel-grid)
                  (dwt/increase-rotation #{(:id rect)} 15)]]
      (ths/run-store
       store done events
       (fn [_new-state]
         (t/is (= [{:snap-pixel? false :snap-ignore-axis nil}]
                  @captured-snap-options)))))))
