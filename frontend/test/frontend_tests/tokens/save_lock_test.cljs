;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.save-lock-test
  "The token values while saves fail and the workspace is locked
  (`app.main.data.persistence.lock`)."
  (:require
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.tokens-lib :as ctob]
   [app.config :as cf]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.tokens.correction :as dwtc]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [frontend-tests.tokens.helpers.propagation :as tohp]
   [frontend-tests.tokens.helpers.state :as tohs]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(defn- lock-saves
  "`store` with the workspace locked because saves fail."
  [store]
  (swap! store assoc-in [:workspace-global :persistence-lock] {:read-only? false})
  store)

(t/deftest a-correction-goes-through-while-saves-fail
  (t/async
    done
    (let [store   (lock-saves (ths/setup-store (tohp/setup-file)))
          commits (tohp/watch-commits store)]
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [state]
         (t/is (= 50 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale))))
         (t/is (some #(= :local (:source %)) @commits) "saved when saves recover"))
       tohp/stop-on-ready))))

(t/deftest a-token-change-is-refused-while-saves-fail
  (t/async
    done
    (let [file    (tohp/setup-file)
          store   (lock-saves (ths/setup-store file))
          commits (tohp/watch-commits store)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))]
      (tohs/run-store
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (t/is (= 50 (:value (ctob/get-token (ctht/get-tokens-lib file') (cthi/id :set) (cthi/id :radius))))
                 "the token keeps its value")
           (t/is (not= 70 (:r1 (cths/get-shape file' :stale))) "and so do the shapes")
           (t/is (empty? (filter #(= :local (:source %)) @commits)))))
       ;; Nothing commits, so it waits for anything that would follow
       (fn [_] (rx/timer 1000))))))

(t/deftest update-shapes-commits-while-saves-fail-only-when-asked
  (t/async
    done
    (let [store   (lock-saves (ths/setup-store (tohp/setup-file)))
          commits (tohp/watch-commits store)
          id      (cthi/id :stale)]
      (tohs/run-store
       store done [(dwsh/update-shapes [id] #(assoc % :name "edited"))
                   (dwsh/update-shapes [id] #(assoc % :name "derived") {:ignore-save-lock true})]
       (fn [state]
         (t/is (= "derived" (:name (cths/get-shape (ths/get-file-from-state state) :stale))))
         (t/is (= 1 (count @commits)) "only the commit that ignores the lock"))))))

(defn- refused-change-sets-no-page
  "Checks that a token change refused while saves fail sets no page, with
  the `flags` of the propagation."
  [done flags]
  (let [previous cf/flags
        _        (set! cf/flags (into previous flags))
        file     (tohp/setup-file-with-pages)
        store    (lock-saves (ths/setup-store file))
        commits  (tohp/watch-commits store)
        drains   (atom 0)
        token    (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))]
    (rx/subs! #(swap! drains inc)
              (rx/filter (ptk/type? :app.main.data.workspace.tokens.drain/start) (ptk/input-stream store)))
    (tohs/run-store
     store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
     (fn [state]
       (set! cf/flags previous)
       (t/is (= 50 (:r1 (cths/get-shape (ths/get-file-from-state state) :other :page-label :page2)))
             "the other page keeps its value")
       (t/is (empty? @commits))
       (t/is (zero? @drains) "no drain"))
     (fn [_] (rx/timer 1000)))))

(t/deftest a-refused-token-change-sets-no-other-page
  (t/async done (refused-change-sets-no-page done #{})))

(t/deftest a-refused-lazy-token-change-drains-no-page
  (t/async done (refused-change-sets-no-page done #{:lazy-token-propagation :lazy-token-propagation-drain})))
