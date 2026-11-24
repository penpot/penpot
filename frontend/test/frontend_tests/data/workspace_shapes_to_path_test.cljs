;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.data.workspace-shapes-to-path-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.types.path :as path]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.path.shapes-to-path :as dwps]
   [app.main.data.workspace.reflow :as wrf]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.api.fonts :as wasm.fonts]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before (fn []
             (cthi/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  (fn []
             (thw/teardown-wasm-mocks!))})

(def ^:private text-font
  {:font-id "gfont-zen-antique"
   :font-variant-id "regular"
   :font-weight "400"
   :font-style "normal"})

(def ^:private outline
  (path/from-plain [{:command :move-to :params {:x 0 :y 0}}
                    {:command :line-to :params {:x 10 :y 0}}
                    {:command :line-to :params {:x 10 :y 10}}
                    {:command :close-path :params {}}]))

(defn- text-store
  []
  (ths/setup-store (-> (cthf/sample-file :file1 :page-label :page1)
                       (ctho/add-text :text1 "漱石"))))

(defn- shape-type
  [store]
  (:type (get (dsh/lookup-page-objects @store) (cthi/id :text1))))

(t/deftest ^:async text-whose-font-still-loads-becomes-a-path-once-the-font-is-stored
  ;; Outlined while its font loads, a text traces the fallback face: the
  ;; conversion waits for the face, then outlines the text.
  (let [stored? (atom false)]
    (await
     (mock/with-mocks*
       {wasm.fonts/get-content-fonts (mock/stub (constantly #{text-font}))
        wasm.fonts/font-stored?      (mock/stub (fn [_ _] @stored?))
        wasm.api/shape-to-path       (mock/stub (constantly outline))}
       (let [store (text-store)
             face  (wasm.fonts/font-data-key (wasm.fonts/make-font-data text-font))]
         (ptk/emit! store (dwps/convert-selected-to-path #{(cthi/id :text1)}))
         (await (async/settle))
         (t/is (= :text (shape-type store)) "the text waits for its font")

         (reset! stored? true)
         (rx/push! wasm.fonts/font-stored-stream face)
         (await (async/wait-for #(= :path (shape-type store))
                                "storing the font converts the text")))))))

(t/deftest ^:async layout-waits-cover-a-conversion-waiting-for-its-font
  ;; `waitForLayoutUpdate` (and the MCP tools after each call) wait on the
  ;; reflow pending map: it must hold the text until it is a path.
  (let [settled? (atom false)]
    (await
     (mock/with-mocks*
       {wasm.fonts/get-content-fonts (mock/stub (constantly #{text-font}))
        wasm.fonts/font-stored?      (mock/stub (constantly false))
        wasm.api/shape-to-path       (mock/stub (constantly outline))}
       (let [store (text-store)
             id    (cthi/id :text1)
             face  (wasm.fonts/font-data-key (wasm.fonts/make-font-data text-font))]
         (ptk/emit! store (dwps/convert-selected-to-path #{id}))
         (rx/subs! (fn [_] (reset! settled? (shape-type store)))
                   (wrf/settled #{id}))
         (await (async/settle))
         (t/is (false? @settled?) "the layout wait is still pending")

         (rx/push! wasm.fonts/font-stored-stream face)
         (await (async/wait-for #(some? @settled?) "the layout wait settles"))
         (t/is (= :path @settled?) "it settles once the text is a path"))))))

(t/deftest ^:async text-with-its-fonts-loaded-becomes-a-path-at-once
  (await
   (mock/with-mocks*
     {wasm.fonts/get-content-fonts (mock/stub (constantly #{text-font}))
      wasm.api/shape-to-path       (mock/stub (constantly outline))}
     (let [store (text-store)]
       (ptk/emit! store (dwps/convert-selected-to-path #{(cthi/id :text1)}))
       (t/is (= :path (shape-type store)))
       (await (async/settle))))))
