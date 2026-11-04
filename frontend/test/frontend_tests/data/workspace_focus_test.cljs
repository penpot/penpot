;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-focus-test
  "Tests for focus mode when the focused shapes go away."
  (:require
   [app.common.data :as d]
   [app.common.types.shape :as cts]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.selection :as dws]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [potok.v2.core :as ptk]))

(defn- focus-store
  "A store on a page holding `ids`, in focus mode on `focused`, entered
  with `selected` selected."
  [ids focused selected]
  (let [file-id (uuid/next)
        page-id (uuid/next)
        objects (into {uuid/zero (cts/setup-shape {:id uuid/zero :type :frame :shapes (vec ids)})}
                      (map (fn [id]
                             [id (cts/setup-shape {:id id :type :rect :name "Rect"
                                                   :parent-id uuid/zero :frame-id uuid/zero})]))
                      ids)
        local   {:selected (into (d/ordered-set) selected) :zoom 1}]
    (ptk/store {:state {:current-file-id file-id
                        :current-page-id page-id
                        :files {file-id {:id file-id
                                         :data {:pages [page-id]
                                                :pages-index {page-id {:id page-id :objects objects}}}}}
                        :workspace-local (assoc local :selected (d/ordered-set))
                        :workspace-pre-focus local
                        :workspace-focus-selected (set focused)}
                :on-error #(t/is false (str %))})))

(defn- remove-shape
  "Takes `id` out of the page of `store`, as a delete or a rollback does."
  [store id]
  (let [{:keys [current-file-id current-page-id]} @store]
    (ptk/emit! store #(update-in % [:files current-file-id :data :pages-index current-page-id :objects]
                                 dissoc id))))

;; Scenario: focus mode is on R, entered with R selected, and R goes away
;; (a rollback or a delete); later a new shape is drawn. Proves: focus mode
;; ends when its last shape goes, the selection from before it comes back
;; without R, and the new shape does not bring focus mode back.
(t/deftest ^:async focus-mode-ends-when-its-last-shape-is-removed
  (let [[r s n] (repeatedly 3 uuid/next)
        store   (focus-store [r s] [r] [r])
        left    (atom 0)
        sub     (->> (ptk/input-stream store)
                     (rx/filter (ptk/type? ::dws/focus-mode-left))
                     (rx/subs! (fn [_] (swap! left inc))))]
    (remove-shape store r)
    (ptk/emit! store (dws/update-focus-shapes #{} #{r}))
    (await (async/settle))
    (t/is (nil? (:workspace-focus-selected @store)) "focus mode is off")
    (t/is (nil? (:workspace-pre-focus @store)))
    (t/is (empty? (get-in @store [:workspace-local :selected]))
          "the removed shape is not selected again")
    (t/is (= 1 (:zoom (:workspace-local @store))) "the viewport from before comes back")
    (ptk/emit! store (dws/update-focus-shapes #{n} #{}))
    (await (async/settle))
    (t/is (nil? (:workspace-focus-selected @store)) "a new shape does not turn it on")
    (t/is (= 1 @left) "the focus watcher is told to stop once")
    (rx/dispose! sub)))

;; Scenario: focus mode is on R and S, entered with both selected; R goes
;; away and the user leaves focus mode with F. Proves: focus mode stays on
;; while a focused shape is left, and leaving it selects only the shapes
;; that still exist.
(t/deftest ^:async leaving-focus-mode-selects-only-shapes-that-exist
  (let [[r s] (repeatedly 2 uuid/next)
        store (focus-store [r s] [r s] [r s])]
    (remove-shape store r)
    (ptk/emit! store (dws/update-focus-shapes #{} #{r}))
    (await (async/settle))
    (t/is (= #{s} (:workspace-focus-selected @store)))
    (ptk/emit! store (dws/toggle-focus-mode))
    (await (async/settle))
    (t/is (nil? (:workspace-focus-selected @store)))
    (t/is (= [s] (vec (get-in @store [:workspace-local :selected]))))))
