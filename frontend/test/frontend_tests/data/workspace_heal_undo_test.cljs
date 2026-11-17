;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-heal-undo-test
  "Automatic text size heals (a text resized to its WASM layout without a
  user action) must not record an undo step, so Ctrl+Z reverts the user's
  own last edit."
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.wasm-text :as dwwt]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before (fn []
             (cthi/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(defn- collect-commits
  "Subscribe before emitting; accumulates the commits that touch `id`."
  [store id]
  (let [acc (atom [])]
    (->> (ptk/input-stream store)
         (rx/filter dch/commit?)
         (rx/map deref)
         (rx/filter #(some (comp #{id} :id) (:redo-changes %)))
         (rx/subs! #(swap! acc conj %)))
    acc))

(defn- touches?
  [commit id]
  (some (comp #{id} :id) (:redo-changes commit)))

(defn- ^:async batch-commit
  "Emit one WASM text resize per entry of `opts-list`, each for its own text,
  in the same debounce window. Resolves to the first commit that touches
  the first text, with the ids of all the texts."
  [& opts-list]
  (let [labels  (map-indexed (fn [i _] [(keyword (str "frame-" i)) (keyword (str "text-" i))])
                             opts-list)
        file    (reduce (fn [file [frame text]]
                          (ctho/add-frame-with-text file frame text "hello"))
                        (cthf/sample-file :file1)
                        labels)
        store   (ths/setup-store file)
        ids     (mapv (fn [[_ text]] (:id (cths/get-shape file text))) labels)
        commits (collect-commits store (first ids))]
    (doseq [[id opts] (map vector ids opts-list)]
      (ptk/emit! store (dwwt/resize-wasm-text-all [id] opts)))
    (await (async/wait-for #(seq @commits) "a resize commit for the text" {:max-ticks 200}))
    {:commit (first @commits) :ids ids}))

(def ^:private heal {:skip-component-sync? true :save-undo? false})

(def ^:private user {:skip-component-sync? true})

(t/deftest ^:async heal-resize-records-no-undo-step
  (let [{:keys [commit]} (await (batch-commit heal))]
    (t/is (false? (:save-undo? commit))
          "the heal commit is not added to the undo history")))

(t/deftest ^:async user-resize-keeps-its-undo-step
  (let [{:keys [commit]} (await (batch-commit user))]
    (t/is (true? (:save-undo? commit))
          "a resize without the flag records undo as today")))

(t/deftest ^:async user-resize-batched-after-a-heal-keeps-its-undo-step
  (let [{:keys [commit ids]} (await (batch-commit heal nil))]
    (t/is (every? #(touches? commit %) ids)
          "both resizes land in one commit")
    (t/is (true? (:save-undo? commit))
          "a user resize without options keeps its undo step")))

(t/deftest ^:async heal-batched-after-a-user-resize-keeps-the-undo-step
  (let [{:keys [commit ids]} (await (batch-commit nil heal))]
    (t/is (every? #(touches? commit %) ids)
          "both resizes land in one commit")
    (t/is (true? (:save-undo? commit))
          "the user's resize keeps its undo step")))

(t/deftest ^:async batch-of-heals-records-no-undo-step
  (let [{:keys [commit ids]} (await (batch-commit heal heal))]
    (t/is (every? #(touches? commit %) ids)
          "both heals land in one commit")
    (t/is (false? (:save-undo? commit))
          "a batch of heals is not added to the undo history")))
