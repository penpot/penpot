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
   [app.common.math :as mth]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.pages :as dwpg]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.texts :as dwt]
   [app.main.data.workspace.thumbnails :as dwth]
   [app.main.data.workspace.thumbnails-wasm :as dwth.wasm]
   [app.main.data.workspace.undo :as dwu]
   [app.main.data.workspace.wasm-text :as dwwt]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [frontend-tests.tokens.helpers.state :as tohs]
   [okulary.core :as l]
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

;; What the component sync sends when it re-measures the synced copies.
(def ^:private sync-remeasure {:skip-component-sync? true})

;; A user resize carries no options.
(def ^:private user nil)

(t/deftest ^:async heal-resize-records-no-undo-step
  (let [{:keys [commit]} (await (batch-commit heal))]
    (t/is (false? (:save-undo? commit))
          "the heal commit is not added to the undo history")))

(t/deftest ^:async user-resize-keeps-its-undo-step
  (let [{:keys [commit]} (await (batch-commit user))]
    (t/is (true? (:save-undo? commit))
          "a user resize records undo")
    (t/is (not (:skip-component-sync? commit))
          "a user resize syncs to component copies")))

(t/deftest ^:async sync-remeasure-keeps-its-undo-step
  (let [{:keys [commit]} (await (batch-commit sync-remeasure))]
    (t/is (true? (:save-undo? commit))
          "a sync re-measure records undo")
    (t/is (true? (:skip-component-sync? commit))
          "a sync re-measure does not start another sync")))

(t/deftest ^:async user-resize-batched-after-a-heal-keeps-its-undo-step
  (let [{:keys [commit ids]} (await (batch-commit heal user))]
    (t/is (every? #(touches? commit %) ids)
          "both resizes land in one commit")
    (t/is (true? (:save-undo? commit))
          "a user resize without options keeps its undo step")
    (t/is (not (:skip-component-sync? commit))
          "a user resize without options still syncs to component copies")))

(t/deftest ^:async heal-batched-after-a-user-resize-keeps-the-undo-step
  (let [{:keys [commit ids]} (await (batch-commit user heal))]
    (t/is (every? #(touches? commit %) ids)
          "both resizes land in one commit")
    (t/is (true? (:save-undo? commit))
          "the user's resize keeps its undo step")
    (t/is (not (:skip-component-sync? commit))
          "the user's resize still syncs to component copies")))

(t/deftest ^:async user-resize-batched-after-a-sync-remeasure-still-syncs
  (let [{:keys [commit ids]} (await (batch-commit sync-remeasure user))]
    (t/is (every? #(touches? commit %) ids)
          "both resizes land in one commit")
    (t/is (true? (:save-undo? commit)))
    (t/is (not (:skip-component-sync? commit))
          "the main's new size reaches its copies")))

(t/deftest ^:async sync-remeasure-batched-after-a-user-resize-still-syncs
  (let [{:keys [commit ids]} (await (batch-commit user sync-remeasure))]
    (t/is (every? #(touches? commit %) ids)
          "both resizes land in one commit")
    (t/is (true? (:save-undo? commit)))
    (t/is (not (:skip-component-sync? commit))
          "the main's new size reaches its copies")))

(t/deftest ^:async batch-of-sync-remeasures-skips-the-sync
  (let [{:keys [commit ids]} (await (batch-commit sync-remeasure sync-remeasure))]
    (t/is (every? #(touches? commit %) ids)
          "both re-measures land in one commit")
    (t/is (true? (:save-undo? commit)))
    (t/is (true? (:skip-component-sync? commit))
          "a batch of re-measures does not start another sync")))

(t/deftest ^:async batch-of-heals-records-no-undo-step
  (let [{:keys [commit ids]} (await (batch-commit heal heal))]
    (t/is (every? #(touches? commit %) ids)
          "both heals land in one commit")
    (t/is (false? (:save-undo? commit))
          "a batch of heals is not added to the undo history")
    (t/is (true? (:skip-component-sync? commit))
          "a batch of heals does not sync to component copies")))

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
    (t/is (some #(and (false? (:save-undo? %))
                      (true? (:skip-component-sync? %))
                      (contains? (set (:ids %)) id))
                @layouts)
          "the parents' reflow is asked for without undo and without sync too")))

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
    (ptk/emit! store (ptk/data-event :layout/update {:ids [rect-a]
                                                     :save-undo? false
                                                     :skip-component-sync? true}))
    (ptk/emit! store (ptk/data-event :layout/update {:ids [rect-b]}))
    (await (async/wait-for #(and (seq @heal) (seq @user)) "a reflow commit for each board"
                           {:max-ticks 400}))
    (ptk/emit! store (dwsl/finalize-shape-layout))
    (t/is (false? (:save-undo? (first @heal)))
          "the reflow of a heal is not added to the undo history")
    ;; A commit without undo and without this flag would make the component
    ;; watcher touch the component, so a page open would change it
    (t/is (true? (:skip-component-sync? (first @heal)))
          "the reflow of a heal does not change components")
    (t/is (true? (:save-undo? (first @user)))
          "a user reflow in the same buffer keeps its undo step")
    (t/is (not (:skip-component-sync? (first @user)))
          "and still syncs to component copies")))

;; Undo history

(defn- undo-stack
  [store]
  (select-keys (:workspace-undo @store) [:items :index]))

(defn- text-size
  [store id]
  (-> (dsh/lookup-page-objects @store)
      (get id)
      (select-keys [:width :height])))

(defn- ^:async heal-keeps-the-undo-history
  "Make a user edit on one text with `user-event`, then heal another with
  `heal-event`, each in its own commit. The heal must leave the undo stack
  as the user edit left it, and undo must revert the user edit."
  [renderer user-event heal-event]
  (let [file         (-> (cthf/sample-file :file1)
                         (ctho/add-frame-with-text :frame-a :text-a "hello")
                         (ctho/add-frame-with-text :frame-b :text-b "world"))
        store        (ths/setup-store file (when renderer {:renderer renderer}))
        user-id      (:id (cths/get-shape file :text-a))
        heal-id      (:id (cths/get-shape file :text-b))
        user-commits (collect-commits store user-id)
        heal-commits (collect-commits store heal-id)
        original     (text-size store user-id)]
    (ptk/emit! store (tohs/watch-undo-stack))

    (ptk/emit! store (user-event user-id))
    (await (async/wait-for #(and (seq @user-commits) (seq (:items (undo-stack store))))
                           "an undo entry for the user edit" {:max-ticks 200}))
    (let [after-user (undo-stack store)
          edited     (text-size store user-id)]
      (t/is (not= original edited)
            "the user edit changes the text size")

      (ptk/emit! store (heal-event heal-id))
      (await (async/wait-for #(seq @heal-commits) "a heal commit" {:max-ticks 200}))
      (await (async/settle))
      (let [healed (text-size store heal-id)]
        (t/is (= after-user (undo-stack store))
              "the heal adds no undo entry and does not move the index")

        (ptk/emit! store dwu/undo)
        (await (async/wait-for #(not= edited (text-size store user-id))
                               "the undo of the user edit" {:max-ticks 200}))
        (t/is (and (mth/close? (:width original) (:width (text-size store user-id)))
                   (mth/close? (:height original) (:height (text-size store user-id))))
              "undo reverts the user edit")
        (t/is (= healed (text-size store heal-id))
              "undo leaves the heal in place")))))

(t/deftest ^:async heal-keeps-the-undo-history-on-wasm
  (await (heal-keeps-the-undo-history
          nil
          #(dwwt/resize-wasm-text-all [%] nil)
          #(dwwt/resize-wasm-text-all [%] heal))))

(t/deftest ^:async heal-keeps-the-undo-history-on-svg
  (await (heal-keeps-the-undo-history
          :svg
          #(dwt/resize-text % 60 40)
          #(dwt/resize-text % 50 30 {:save-undo? false}))))

;; Main text edit, its own resize and the component sync

(defn- get-shape-in-store
  [store id]
  (get (dsh/lookup-page-objects @store) id))

(defn- noop-render-thumbnail
  [& _]
  (ptk/reify ::noop-render-thumbnail
    ptk/WatchEvent
    (watch [_ _ _] (rx/empty))))

;; Scenario: the user edits a main text, the main text then resizes itself
;; (`resize-text`, a stacked commit in its own undo transaction), and the
;; real component watcher syncs both changes to the copy. Proves: one Ctrl+Z
;; reverts the edit, the resize and the syncs, on the main and on the copy.
;; Before, the resize kept the group of its transaction, the sync it started
;; inherited it and became an undo entry of its own, so the first Ctrl+Z
;; undid only that sync.
(t/deftest ^:async one-undo-reverts-a-main-text-edit-its-resize-and-their-sync
  (let [file    (-> (cthf/sample-file :file1)
                    (ctho/add-frame-with-text :main-root :main-child "hello world"
                                              :child-params {:grow-type :auto-height})
                    (cthc/make-component :component1 :main-root)
                    (cthc/instantiate-component :component1 :copy-root
                                                {:children-labels [:copy-child]}))
        store   (ths/setup-store file {:renderer :svg})
        page-id (cthf/current-page-id file)
        main-id (cthi/id :main-child)
        copy-id (cthi/id :copy-child)
        before  (fn [id] (select-keys (get-shape-in-store store id) [:opacity :width :height]))
        main-0  (before main-id)
        copy-0  (before copy-id)]
    (await
     (mock/with-mocks*
       {;; The component watcher reads the file data through this lens
        refs/workspace-data        (l/derived dsh/lookup-file-data store)
        ;; Thumbnail renders read the global store
        st/state                   store
        dwth.wasm/render-thumbnail noop-render-thumbnail
        ;; The SVG renderer updates the main's thumbnail after the sync; its
        ;; render needs a DOM and finishes after the test
        dwth/update-thumbnail      noop-render-thumbnail
        ;; sync-file clears frame thumbnails on a timer that outlives the test
        dwth/clear-thumbnail       (mock/stub (constantly nil))}

       (ptk/emit! store (tohs/watch-undo-stack))
       (ptk/emit! store (dwl/watch-component-changes))

       ;; The user edit, synced to the copy
       (ptk/emit! store (dwsh/update-shapes [main-id] #(assoc % :opacity 0.5)))
       (await (async/wait-for #(= 0.5 (:opacity (get-shape-in-store store copy-id)))
                              "the edit to reach the copy" {:max-ticks 400}))

       ;; The main text resizes itself, synced to the copy
       (ptk/emit! store (dwt/resize-text main-id 60 40))
       (await (async/wait-for #(mth/close? 40 (:height (get-shape-in-store store copy-id)))
                              "the resize to reach the copy" {:max-ticks 400}))
       (await (async/settle))

       (let [index (get-in @store [:workspace-undo :index])]
         (ptk/emit! store dwu/undo)
         (await (async/wait-for #(not= index (get-in @store [:workspace-undo :index]))
                                "the undo" {:max-ticks 400})))
       (await (async/settle))

       (t/is (= -1 (get-in @store [:workspace-undo :index]))
             "one undo reverts the whole action")
       (t/is (= main-0 (before main-id))
             "the main is back to its size and opacity")
       (t/is (= copy-0 (before copy-id))
             "the copy is back to its size and opacity")

       (ptk/emit! store (dwpg/finalize-page (:id file) page-id))
       (await (async/settle))))))
