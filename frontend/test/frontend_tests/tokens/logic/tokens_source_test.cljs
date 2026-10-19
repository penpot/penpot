;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.logic.tokens-source-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.repo :as rp]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(defn- setup-library
  "A library whose active set defines `red` as green."
  []
  (ctht/sample-file-with-tokens
   :file-id :library
   :lib-fn #(-> %
                (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :library-set)
                                                   :name "library-set"))
                (ctob/add-token (cthi/id :library-set)
                                (ctob/make-token :id (cthi/new-id! :library-red)
                                                 :name "red"
                                                 :type :color
                                                 :value "#00ff00")))
   :status-fn #(ctos/set-tokens-status % #{} #{(cthi/id :library-set)})))

(defn- setup-file
  "A file whose own active set defines `red` as dark red, applied to the
  fill of a rectangle."
  []
  (-> (ctht/sample-file-with-tokens
       :file-id :local
       :lib-fn #(-> %
                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :local-set)
                                                       :name "local-set"))
                    (ctob/add-token (cthi/id :local-set)
                                    (ctob/make-token :id (cthi/new-id! :local-red)
                                                     :name "red"
                                                     :type :color
                                                     :value "rgb(123, 34, 34)")))
       :status-fn #(ctos/set-tokens-status % #{} #{(cthi/id :local-set)}))
      (ctho/add-rect :rect1)
      (ctht/apply-token-to-shape :rect1 "red" [:fill] [:fill] "#7b2222")))

(defn- fill-color
  [store]
  (let [file (ths/get-file-from-state @store)]
    (-> (cths/get-shape file :rect1) :fills first :fill-color)))

(defn- active-set-ids
  [store]
  (-> (ths/get-file-from-state @store) ctht/get-tokens-status ctos/get-active-set-ids))

(defn- tokens-source
  [store]
  (-> (ths/get-file-from-state @store) ctht/get-tokens-source))

(defn- ^:async use-library-tokens
  "Set `library` as tokens source and wait for the canvas to use it."
  [store library]
  (ptk/emit! store (dwtl/set-tokens-source (:id library)))
  (await (async/wait-for #(= "#00ff00" (fill-color store))
                         "the fill uses the library value of `red`"
                         {:max-ticks 200})))

(t/deftest ^:async switching-tokens-source-updates-the-canvas
  (let [library (setup-library)
        file    (setup-file)
        store   (ths/setup-store file {:libraries [library]})]

    (t/is (= "#7b2222" (fill-color store)))

    (ptk/emit! store (dwtl/set-tokens-source (:id library)))
    (await (async/wait-for #(= "#00ff00" (fill-color store))
                           "the fill uses the library value of `red`"
                           {:max-ticks 200}))
    (t/is (= "#00ff00" (fill-color store))
          "Setting the library as tokens source resolves `red` from the library")

    (ptk/emit! store (dwtl/set-tokens-source (:id file)))
    (await (async/settle))
    (t/is (contains? (active-set-ids store) (cthi/id :local-set))
          "Going back to the local tokens keeps the local set active")
    (await (async/wait-for #(= "#7b2222" (fill-color store))
                           "the fill uses the local value of `red` again"
                           {:max-ticks 200}))
    (t/is (= "#7b2222" (fill-color store))
          "Going back to the local tokens resolves `red` from the file again")

    (rx/dispose! store)))

(t/deftest ^:async unlinking-the-tokens-source-library-restores-the-local-tokens
  (let [library (setup-library)
        file    (setup-file)
        store   (ths/setup-store file {:libraries [library]})]
    (await (use-library-tokens store library))

    (await
     (mock/with-mocks*
       {rp/cmd! (mock/stub (fn [_ _] (rx/observe-on :async (rx/of []))))}
       ;; The same events the libraries dialog emits on unlink
       (ptk/emit! store
                  (dwl/unlink-file-from-library (:id file) (:id library))
                  (dwtl/set-tokens-source (:id file)))
       (await (async/wait-for #(= "#7b2222" (fill-color store))
                              "the fill uses the local value of `red` again"
                              {:max-ticks 200}))))

    (t/is (nil? (tokens-source store)))
    (t/is (contains? (active-set-ids store) (cthi/id :local-set))
          "Unlinking the library keeps the local set active")
    (t/is (= "#7b2222" (fill-color store)))

    (rx/dispose! store)))

