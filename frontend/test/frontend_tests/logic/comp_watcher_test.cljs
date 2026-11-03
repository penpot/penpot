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
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.pages :as dwpg]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.thumbnails-wasm :as dwt.wasm]
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

       (await (async/wait-for #(empty? (copy-children store))
                              "the copy to lose its rect"))

       (let [file' (ths/get-file-from-state @store)]
         (t/is (= (cthi/id :outside)
                  (:parent-id (cths/get-shape file' :main-child)))
               "the rect moved out of the main")
         (t/is (empty? (copy-children store))
               "the copy lost its rect"))

       (ptk/emit! store (dwpg/finalize-page (:id file) (cthf/current-page-id file)))
       (await (async/settle))))))
