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
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.texts :as dwt]
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

;; SVG renderer

(defn- collect-layout-updates
  "Subscribe before emitting; accumulates `:layout/update` payloads."
  [store]
  (let [acc (atom [])]
    (->> (ptk/input-stream store)
         (rx/filter (ptk/type? :layout/update))
         (rx/map deref)
         (rx/subs! #(swap! acc conj %)))
    acc))

(t/deftest ^:async svg-heal-resize-records-no-undo-step
  (let [file    (-> (cthf/sample-file :file1)
                    (ctho/add-frame-with-text :frame :text "hello"))
        store   (ths/setup-store file {:renderer :svg})
        id      (:id (cths/get-shape file :text))
        commits (collect-commits store id)
        layouts (collect-layout-updates store)]
    (ptk/emit! store (dwt/resize-text id 50 30 {:save-undo? false}))
    (await (async/wait-for #(seq @commits) "a resize commit for the text" {:max-ticks 200}))
    (t/is (false? (:save-undo? (first @commits)))
          "the heal commit is not added to the undo history")
    (t/is (true? (:skip-component-sync? (first @commits)))
          "the heal does not sync to component copies")
    (t/is (some #(and (false? (:save-undo? %)) (contains? (set (:ids %)) id)) @layouts)
          "the parents' reflow is asked for without undo too")))

(defn- ^:async svg-mixed-commits
  "Emit `heal-event` for one text and `user-event` for another in the same
  debounce window; resolve to the first commit that touches each, as
  `[heal-commit user-commit]`."
  [heal-event user-event]
  (let [file    (-> (cthf/sample-file :file1)
                    (ctho/add-frame-with-text :frame-a :text-a "hello")
                    (ctho/add-frame-with-text :frame-b :text-b "world"))
        store   (ths/setup-store file {:renderer :svg})
        heal-id (:id (cths/get-shape file :text-a))
        user-id (:id (cths/get-shape file :text-b))
        heal    (collect-commits store heal-id)
        user    (collect-commits store user-id)]
    (ptk/emit! store (heal-event heal-id))
    (ptk/emit! store (user-event user-id))
    (await (async/wait-for #(and (seq @heal) (seq @user)) "a commit for each text"
                           {:max-ticks 400}))
    [(first @heal) (first @user)]))

(t/deftest ^:async svg-resize-batch-keeps-the-undo-step-of-a-user-resize
  (let [[heal user] (await (svg-mixed-commits #(dwt/resize-text % 50 30 {:save-undo? false})
                                              #(dwt/resize-text % 60 40)))]
    (t/is (false? (:save-undo? heal))
          "the heal in the batch records no undo step")
    (t/is (true? (:save-undo? user))
          "the user resize in the same batch keeps its undo step")
    (t/is (not (:skip-component-sync? user))
          "the user resize still syncs to component copies")))

(def ^:private position-data
  [{:x 0 :y 0 :width 10 :height 10 :text "x" :font-size "14" :font-family "sourcesanspro"
    :font-weight "400" :font-style "normal" :text-decoration "none"
    :text-transform "none" :letter-spacing "normal" :direction "ltr"
    :fills [{:fill-color "#000000" :fill-opacity 1}]}])

(t/deftest ^:async svg-position-data-batch-keeps-the-undo-step-of-a-user-change
  (let [[heal user] (await (svg-mixed-commits #(dwt/update-position-data % position-data {:save-undo? false})
                                              #(dwt/update-position-data % position-data)))]
    (t/is (false? (:save-undo? heal))
          "the heal in the batch records no undo step")
    (t/is (true? (:skip-component-sync? heal))
          "the heal does not sync to component copies")
    (t/is (true? (:save-undo? user))
          "the user change in the same batch keeps its undo step")
    (t/is (not (:skip-component-sync? user))
          "the user change still syncs to component copies")))

(defn- ^:async svg-same-text-commit
  "Emit `heal-event` and then `user-event` for the same text in one debounce
  window; resolve to the first commit that touches it."
  [heal-event user-event]
  (let [file    (-> (cthf/sample-file :file1)
                    (ctho/add-frame-with-text :frame :text "hello"))
        store   (ths/setup-store file {:renderer :svg})
        id      (:id (cths/get-shape file :text))
        commits (collect-commits store id)]
    (ptk/emit! store (heal-event id))
    (ptk/emit! store (user-event id))
    (await (async/wait-for #(seq @commits) "a commit for the text" {:max-ticks 200}))
    (first @commits)))

(t/deftest ^:async svg-user-resize-after-a-heal-of-the-same-text-keeps-undo
  (let [commit (await (svg-same-text-commit #(dwt/resize-text % 50 30 {:save-undo? false})
                                            #(dwt/resize-text % 60 40)))]
    (t/is (true? (:save-undo? commit))
          "the later user resize decides: it keeps its undo step")
    (t/is (not (:skip-component-sync? commit))
          "and syncs to component copies")))

(t/deftest ^:async svg-user-change-after-a-heal-of-the-same-text-keeps-undo
  (let [commit (await (svg-same-text-commit #(dwt/update-position-data % position-data {:save-undo? false})
                                            #(dwt/update-position-data % position-data)))]
    (t/is (true? (:save-undo? commit))
          "the later user change decides: it keeps its undo step")
    (t/is (not (:skip-component-sync? commit))
          "and syncs to component copies")))

(defn- flex-board-with-rect
  "A flex column board with a rect placed off its layout position, so a
  reflow of the board always moves the rect."
  [file board-label rect-label x]
  (-> file
      (ctho/add-frame board-label {:x x :y 0 :width 200 :height 200
                                   :layout :flex :layout-flex-dir :column})
      (ctho/add-rect rect-label {:parent-label board-label
                                 :x (+ x 50) :y 50 :width 20 :height 20})))

(t/deftest ^:async reflow-of-a-heal-records-no-undo-step
  (let [file   (-> (cthf/sample-file :file1)
                   (flex-board-with-rect :board-a :rect-a 0)
                   (flex-board-with-rect :board-b :rect-b 300))
        store  (ths/setup-store file {:renderer :svg})
        rect-a (:id (cths/get-shape file :rect-a))
        rect-b (:id (cths/get-shape file :rect-b))
        heal   (collect-commits store rect-a)
        user   (collect-commits store rect-b)]
    (ptk/emit! store (dwsl/initialize-shape-layout))
    ;; Both updates land in the same 100 ms layout buffer.
    (ptk/emit! store (ptk/data-event :layout/update {:ids [rect-a] :save-undo? false}))
    (ptk/emit! store (ptk/data-event :layout/update {:ids [rect-b]}))
    (await (async/wait-for #(and (seq @heal) (seq @user)) "a reflow commit for each board"
                           {:max-ticks 400}))
    (ptk/emit! store (dwsl/finalize-shape-layout))
    (t/is (false? (:save-undo? (first @heal)))
          "the reflow of a heal is not added to the undo history")
    (t/is (true? (:save-undo? (first @user)))
          "a user reflow in the same buffer keeps its undo step")))
