;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL
(ns frontend-tests.logic.comp-watcher-test
  "Automatic propagation of main edits to copies, driven by the real
  `dwl/watch-component-changes` on an isolated store."
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.pages :as dwpg]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.thumbnails-wasm :as dwt.wasm]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [okulary.core :as l]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(defn- setup-file
  []
  ;; {:main-root} [:name Frame1]       # [Component :component1]
  ;;     :main-child [:name Rect1]
  ;;
  ;; :copy-root [:name Frame1]         #--> [Component :component1] :main-root
  ;;     :copy-child [:name Rect1]     ---> :main-child
  ;;
  ;; :outside [:name Frame1]
  (-> (cthf/sample-file :file1)
      (ctho/add-simple-component-with-copy :component1
                                           :main-root
                                           :main-child
                                           :copy-root
                                           :copy-root-params {:children-labels [:copy-child]})
      (ctho/add-frame :outside)))

(defn- noop-render-thumbnail
  [& _]
  (ptk/reify ::noop-render-thumbnail
    ptk/WatchEvent
    (watch [_ _ _] (rx/empty))))

(defn- copy-children
  [store]
  (let [file (ths/get-file-from-state @store)]
    (:shapes (cths/get-shape file :copy-root))))

(t/deftest ^:async child-moved-out-of-main-leaves-copy-when-another-edit-follows
  ;; The move and the fill change commit in the same tick, so the watcher
  ;; inspects both in one batch. The move must be checked against the file
  ;; data right before it, where the rect still sits in the main.
  (let [file  (setup-file)
        store (ths/setup-store file)]
    (await
     (mock/with-mocks*
       {;; The watcher reads the file data through this lens.
        refs/workspace-data      (l/derived dsh/lookup-file-data store)
        ;; Thumbnail renders read the global store.
        st/state                 store
        dwt.wasm/render-thumbnail noop-render-thumbnail}

       (ptk/emit! store (dwl/watch-component-changes))
       (t/is (= 1 (count (copy-children store)))
             "the copy starts with its rect")

       (ptk/emit! store
                  (dwsh/relocate-shapes #{(cthi/id :main-child)} (cthi/id :outside) 0)
                  (dwsh/update-shapes [(cthi/id :outside)]
                                      #(assoc % :fills [{:fill-color "#ff0000"
                                                         :fill-opacity 1}])))

       (await (async/wait-for-store store #(empty? (copy-children store))
                                    "the copy to lose its rect"))

       (let [file' (ths/get-file-from-state @store)]
         (t/is (= (cthi/id :outside)
                  (:parent-id (cths/get-shape file' :main-child)))
               "the rect moved out of the main")
         (t/is (empty? (copy-children store))
               "the copy lost its rect"))

       (ptk/emit! store (dwpg/finalize-page (:id file) (cthf/current-page-id file)))
       (await (async/settle))))))

(defn- setup-file-with-token
  "Like `setup-file`, with a radius token applied to the main and the copy,
  whose stored radius differs from the token value."
  []
  (-> (ctht/sample-file-with-tokens
       :lib-fn #(-> %
                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :token-set)
                                                       :name "token-set"))
                    (ctob/add-token (cthi/id :token-set)
                                    (ctob/make-token :name "radius"
                                                     :type :border-radius
                                                     :value 50)))
       :status-fn #(ctos/set-tokens-status % #{} #{(cthi/id :token-set)}))
      (ctho/add-simple-component-with-copy :component1
                                           :main-root
                                           :main-child
                                           :copy-root
                                           :copy-root-params {:children-labels [:copy-child]})
      (ctht/apply-token-to-shape :main-root "radius" [:r1 :r2 :r3 :r4] [:r1 :r2 :r3 :r4] 10)
      (ctht/apply-token-to-shape :copy-root "radius" [:r1 :r2 :r3 :r4] [:r1 :r2 :r3 :r4] 10)))

(t/deftest ^:async token-propagation-does-not-sync-copies-again
  ;; The propagation updates the main and the copy itself, so the watcher
  ;; only marks the component as modified, once.
  (let [file    (setup-file-with-token)
        store   (ths/setup-store file)
        emitted (atom [])
        sub     (rx/subs! #(swap! emitted conj (ptk/type %))
                          (ptk/input-stream store))
        radius  #(:r1 (cths/get-shape (ths/get-file-from-state @store) %))]
    (await
     (mock/with-mocks*
       {refs/workspace-data      (l/derived dsh/lookup-file-data store)
        st/state                 store
        dwt.wasm/render-thumbnail noop-render-thumbnail}

       (ptk/emit! store (dwl/watch-component-changes))
       (ptk/emit! store (dwtp/propagate-workspace-tokens))

       (await (async/wait-for #(some #{::dwl/touch-components} @emitted)
                              "the component to be marked as modified"
                              {:max-ticks 500}))
       (await (async/settle))

       (t/is (= 50 (radius :main-root)) "the main gets the token value")
       (t/is (= 50 (radius :copy-root)) "the copy gets the token value")
       (t/is (= 1 (count (filter #{::dwl/touch-components} @emitted)))
             "the component is marked as modified once")
       (t/is (not-any? #{::dwl/component-changed ::dwl/launch-component-sync} @emitted)
             "the copies are not synced again")

       (ptk/emit! store (dwpg/finalize-page (:id file) (cthf/current-page-id file)))
       (await (async/settle))
       (rx/dispose! sub)))))

(defn- ^:async marks-the-component-once
  "Commits of a propagation that are not undoable are not undos: the
  component is marked as modified once, when `end` is emitted."
  [end]
  (let [file    (setup-file)
        store   (ths/setup-store file)
        emitted (atom [])
        sub     (rx/subs! #(swap! emitted conj (ptk/type %))
                          (ptk/input-stream store))
        page-id (cthf/current-page-id file)
        commit  (fn [radius]
                  (dch/commit-changes
                   {:redo-changes [{:type :mod-obj
                                    :page-id page-id
                                    :id (cthi/id :main-child)
                                    :operations [{:type :set :attr :r1 :val radius}]}]
                    :undo-changes []
                    :save-undo? false
                    :tags #{:token-propagation}}))]
    (await
     (mock/with-mocks*
       {refs/workspace-data      (l/derived dsh/lookup-file-data store)
        st/state                 store
        dwt.wasm/render-thumbnail noop-render-thumbnail}

       (ptk/emit! store (dwl/watch-component-changes))
       (ptk/emit! store (commit 20))
       (await (async/settle))
       (ptk/emit! store (commit 30))
       (await (async/settle))
       (ptk/emit! store end)

       (await (async/wait-for #(some #{::dwl/touch-components} @emitted)
                              "the component to be marked as modified"
                              {:max-ticks 500}))
       (await (async/settle))

       (t/is (= 1 (count (filter #{::dwl/touch-components} @emitted)))
             "the component is marked as modified once")
       (t/is (not-any? #{::dwl/touch-component ::dwl/component-changed} @emitted)
             "not as an undo nor as an edit")

       (ptk/emit! store (dwpg/finalize-page (:id file) page-id))
       (await (async/settle))
       (rx/dispose! sub)))))

(t/deftest ^:async a-propagation-in-the-background-marks-the-component-once
  (await (marks-the-component-once (ptk/data-event ::dwtp/propagation-finished nil))))

;; Scenario: a rollback reverts the opacity of the main's child. A rollback
;; is not a commit, so the change watch sees no component change.
;; Proves: the component's thumbnail is rendered again and uploaded, so
;; neither the tab nor the server keeps a thumbnail of the rejected data.
(t/deftest ^:async a-rollback-of-a-main-renders-and-uploads-its-thumbnail
  (let [file    (setup-file)
        store   (ths/setup-store file)
        page-id (cthf/current-page-id file)
        renders (atom [])]
    (await
     (mock/with-mocks*
       {refs/workspace-data      (l/derived dsh/lookup-file-data store)
        st/state                 store
        dwt.wasm/render-thumbnail (fn [_ _ frame-id & {:as opts}]
                                    (swap! renders conj [frame-id opts])
                                    (noop-render-thumbnail))}

       (ptk/emit! store (dwl/watch-component-changes))
       (ptk/emit! store (dch/rebase-commits
                         (:id file)
                         {:undo   []
                          :remote [{:type :mod-obj :id (cthi/id :main-child) :page-id page-id
                                    :operations [{:type :set :attr :opacity :val 0.5}]}]
                          :redo   []}))

       (await (async/wait-for #(seq @renders) "the thumbnail to render"))
       (t/is (= [[(cthi/id :main-root) {:persist? true}]] @renders))

       (ptk/emit! store (dwpg/finalize-page (:id file) page-id))
       (await (async/settle))))))
