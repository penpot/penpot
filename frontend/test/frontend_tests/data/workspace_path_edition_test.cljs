;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-path-edition-test
  (:require
   [app.common.data :as d]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.shortcuts :as dsc]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.path.shortcuts :as psc]
   [app.main.data.workspace.selection :as dws]
   [app.main.data.workspace.shortcuts :as wsc]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(defn- enter-command?
  [command]
  (if (vector? command)
    (some #(= % "enter") command)
    (= command "enter")))

(t/deftest test-enter-key-is-bound-once-while-path-editing
  ;; Regression test for the physical "enter" key ending up bound to
  ;; two different shortcuts at once while path editing is active: one
  ;; that (re)enters edition mode and one that exits it. `push-shortcuts`
  ;; merges shortcut groups by map key (see `app.main.data.shortcuts`),
  ;; not by physical key/command, so two shortcuts under different keys
  ;; that both claim "enter" survive the merge and both would fire on a
  ;; single keypress, breaking the toggle.
  (let [file  (cthf/sample-file :file1)
        store (ths/setup-store file)]
    (ptk/emit! store (dsc/push-shortcuts ::workspace wsc/shortcuts :workspace))
    (ptk/emit! store (dsc/push-shortcuts ::path psc/shortcuts :workspace :merge-shortcuts :auto))
    (let [effective (get-in @store [:shortcuts ::path])
          matches   (->> effective
                         (filter (fn [[_ sc]] (enter-command? (:command sc))))
                         (map first))]
      (t/is (= 1 (count matches))
            (str "expected exactly one shortcut bound to \"enter\" while path editing, got " matches)))))

(defn- run-scenario
  [shape-type]
  (let [file     (-> (cthf/sample-file :file1)
                     (cths/add-sample-shape :test-shape :type shape-type))
        shape-id (:id (cths/get-shape file :test-shape))
        store    (ths/setup-store file)]
    ;; Select the shape, then reproduce what a physical Enter keypress
    ;; now dispatches at each step: `start-editing-selected` to enter
    ;; path edition mode, `esc-pressed` (-> :interrupt) to exit it, and
    ;; `start-editing-selected` again to re-enter.
    (ptk/emit! store (dws/select-shapes (d/ordered-set shape-id)))

    (ptk/emit! store (dw/start-editing-selected))
    (t/is (= shape-id (get-in @store [:workspace-local :edition]))
          (str "expected " (name shape-type) " to enter path edition mode"))

    (ptk/emit! store (psc/esc-pressed))
    (t/is (nil? (get-in @store [:workspace-local :edition]))
          (str "expected " (name shape-type) " to exit path edition mode"))
    (t/is (= #{shape-id} (get-in @store [:workspace-local :selected]))
          (str "expected " (name shape-type) " to remain selected after exiting path edition mode"))

    (ptk/emit! store (dw/start-editing-selected))
    (t/is (= shape-id (get-in @store [:workspace-local :edition]))
          (str "expected " (name shape-type) " to enter path edition mode again"))))

(t/deftest test-enter-toggles-path-editing-mode
  (doseq [shape-type [:rect :circle :path :image]]
    (run-scenario shape-type)))
