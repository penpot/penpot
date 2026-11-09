;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-undo-translation-test
  "The `:translation` flag of undo entries. Undo, redo and undo-to-index
  replay changes through `commit-changes`, and a replay of a commit that only
  moved shapes must say so, so that nothing downstream reacts to it as it
  would to an edit."
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.uuid :as uuid]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.undo :as dwu]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

(defn- setup
  []
  (let [file  (-> (cthf/sample-file :file1)
                  (ctho/add-frame :frame1))
        store (ths/setup-store file)]
    {:store    store
     :page-id  (cthf/current-page-id file)
     :frame-id (cthi/id :frame1)
     :commits  (atom [])}))

(defn- set-attr
  [{:keys [page-id frame-id]} attr val]
  {:type :mod-obj
   :id frame-id
   :page-id page-id
   :operations [{:type :set :attr attr :val val}]})

(defn- move-entry
  "An undo entry for a move, with the given `translation?` flag."
  [ctx translation? & {:keys [undo-group]}]
  {:undo-changes [(set-attr ctx :x 0)]
   :redo-changes [(set-attr ctx :x 10)]
   :undo-group (or undo-group (uuid/next))
   :tags #{}
   :translation translation?})

(defn- edit-entry
  "An undo entry for an edit that is not a move."
  [ctx translation? & {:keys [undo-group]}]
  (-> (move-entry ctx translation? :undo-group undo-group)
      (assoc :undo-changes [(set-attr ctx :opacity 1)]
             :redo-changes [(set-attr ctx :opacity 0.5)])))

(defn- observe-commits
  "Starts collecting the data of every commit the store sees."
  [{:keys [store commits]}]
  (->> (ptk/input-stream store)
       (rx/filter dch/commit?)
       (rx/map deref)
       (rx/subs! #(swap! commits conj %))))

(defn- ^:async emit-and-wait
  "Emits `event` and resolves with the commit it caused."
  [{:keys [store commits]} event]
  (let [before (count @commits)]
    (ptk/emit! store event)
    (await (async/wait-for #(< before (count @commits)) "the commit of the event"))
    (last @commits)))

(defn- append-all
  [{:keys [store]} entries]
  (doseq [entry entries]
    (ptk/emit! store (dwu/append-undo entry false))))

(t/deftest ^:async undo-and-redo-replay-with-the-flag-of-the-entry
  (let [ctx (setup)]
    (observe-commits ctx)

    (t/testing "a move"
      (append-all ctx [(move-entry ctx true)])
      (t/is (true? (:translation? (await (emit-and-wait ctx dwu/undo)))))
      (t/is (true? (:translation? (await (emit-and-wait ctx dwu/redo))))))

    (t/testing "an edit that is not a move"
      (append-all ctx [(edit-entry ctx false)])
      (t/is (false? (:translation? (await (emit-and-wait ctx dwu/undo)))))
      (t/is (false? (:translation? (await (emit-and-wait ctx dwu/redo))))))))

(t/deftest entry-without-the-translation-flag-is-rejected
  (let [ctx   (setup)
        entry (dissoc (move-entry ctx true) :translation)]
    (t/is (thrown? js/Error (dwu/append-undo entry false)))))

(t/deftest ^:async undo-to-index-replays-a-translation-only-if-all-entries-are
  (let [ctx (setup)]
    (observe-commits ctx)
    (append-all ctx [(move-entry ctx true)
                     (move-entry ctx true)
                     (move-entry ctx true)])

    (t/is (true? (:translation? (await (emit-and-wait ctx (dwu/undo-to-index -1)))))
          "undoing three moves")
    (t/is (true? (:translation? (await (emit-and-wait ctx (dwu/undo-to-index 2)))))
          "redoing three moves")

    (append-all ctx [(move-entry ctx true)
                     (edit-entry ctx false)
                     (move-entry ctx true)])

    (t/is (not (true? (:translation? (await (emit-and-wait ctx (dwu/undo-to-index 2))))))
          "undoing a move, an edit and a move")
    (t/is (not (true? (:translation? (await (emit-and-wait ctx (dwu/undo-to-index 5))))))
          "redoing a move, an edit and a move")

    (t/is (true? (:translation? (await (emit-and-wait ctx (dwu/undo-to-index 4)))))
          "undoing only the last move of those")))

(t/deftest ^:async undo-of-a-group-replays-a-translation-only-if-all-entries-are
  (let [ctx     (setup)
        group-a (uuid/next)
        group-b (uuid/next)]
    (observe-commits ctx)

    (append-all ctx [(move-entry ctx true :undo-group group-a)
                     (move-entry ctx true :undo-group group-a)])
    (t/is (true? (:translation? (await (emit-and-wait ctx dwu/undo))))
          "a group of moves")

    (append-all ctx [(move-entry ctx true :undo-group group-b)
                     (edit-entry ctx false :undo-group group-b)])
    (t/is (not (true? (:translation? (await (emit-and-wait ctx dwu/undo)))))
          "a group with an edit")))

(defn- merged-flag
  "The flag of the only undo entry left after adding one entry per flag of
  `flags`, either stacked on each other or accumulated in a transaction."
  [mode flags]
  (let [ctx     (setup)
        entries (map #(move-entry ctx %) flags)
        events  (case mode
                  :stacked
                  (cons (dwu/append-undo (first entries) false)
                        (map #(dwu/append-undo % true) (rest entries)))

                  :transaction
                  (concat [(dwu/start-undo-transaction ::tx)]
                          (map #(dwu/append-undo % false) entries)
                          [(dwu/commit-undo-transaction ::tx)]))
        state   (reduce (fn [state event] (ptk/update event state))
                        @(:store ctx)
                        events)
        items   (get-in state [:workspace-undo :items])]
    (t/is (= 1 (count items)) (str mode " " (pr-str flags)))
    (:translation (first items))))

(t/deftest merged-entries-are-a-translation-only-if-all-parts-are
  (doseq [mode [:stacked :transaction]]
    (t/testing (name mode)
      (t/is (true? (merged-flag mode [true true])))
      (t/is (true? (merged-flag mode [true true true])))
      (t/is (not (true? (merged-flag mode [true false]))))
      (t/is (not (true? (merged-flag mode [false true]))))
      (t/is (not (true? (merged-flag mode [true false true])))))))
