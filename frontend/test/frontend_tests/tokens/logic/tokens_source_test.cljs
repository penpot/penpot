;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.logic.tokens-source-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.files.tokens :as cfo]
   [app.common.logic.tokens-propagation :as cltp]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.config :as cf]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.tokens.correction :as dwtc]
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
  (await (async/wait-for-store store #(= "#00ff00" (fill-color store))
                               "the fill uses the library value of `red`")))

(t/deftest ^:async switching-tokens-source-updates-the-canvas
  (let [library (setup-library)
        file    (setup-file)
        store   (ths/setup-store file {:libraries [library]})]

    (t/is (= "#7b2222" (fill-color store)))

    (ptk/emit! store (dwtl/set-tokens-source (:id library)))
    (await (async/wait-for-store store #(= "#00ff00" (fill-color store))
                                 "the fill uses the library value of `red`"))
    (t/is (= "#00ff00" (fill-color store))
          "Setting the library as tokens source resolves `red` from the library")

    (ptk/emit! store (dwtl/set-tokens-source (:id file)))
    ;; The change goes through the propagation, after the tokens resolve
    (await (async/wait-for-store store #(contains? (active-set-ids store) (cthi/id :local-set))
                                 "the local set is active again"))
    (t/is (contains? (active-set-ids store) (cthi/id :local-set))
          "Going back to the local tokens keeps the local set active")
    (await (async/wait-for-store store #(= "#7b2222" (fill-color store))
                                 "the fill uses the local value of `red` again"))
    (t/is (= "#7b2222" (fill-color store))
          "Going back to the local tokens resolves `red` from the file again")

    (rx/dispose! store)))

(t/deftest ^:async unlinking-the-tokens-source-library-restores-the-local-tokens
  (let [library (setup-library)
        file    (setup-file)
        store   (ths/setup-store file {:libraries [library]})]
    (await (use-library-tokens store library))

    (let [calls  (atom [])
          calls$ (rx/subject)]
      (await
       (mock/with-mocks*
         {rp/cmd! (mock/stub (fn [cmd _]
                               (swap! calls conj cmd)
                               (rx/push! calls$ cmd)
                               (rx/observe-on :async (rx/of []))))}
         ;; The same events the libraries dialog emits on unlink
         (ptk/emit! store
                    (dwl/unlink-file-from-library (:id file) (:id library))
                    (dwtl/set-tokens-source (:id file)))
         ;; The unlink event dissocs the library before its watch runs, so the
         ;; store cannot tell when the chain ended. The chain's second call
         ;; (`get-file-libraries`) is the proof that it ran inside the mock;
         ;; wait for it, and keep the store signal for the token resolution.
         (await (async/wait-for #(and (= "#7b2222" (fill-color store))
                                      (some #{:get-file-libraries} @calls))
                                "the unlink flow completes and the local tokens resolve"
                                {:signal (rx/merge calls$ (ptk/input-stream store))})))))

    (t/is (nil? (tokens-source store)))
    (t/is (contains? (active-set-ids store) (cthi/id :local-set))
          "Unlinking the library keeps the local set active")
    (t/is (= "#7b2222" (fill-color store)))

    (rx/dispose! store)))


(t/deftest ^:async a-tokens-source-loaded-after-the-page-sets-it
  (let [library (setup-library)
        file    (-> (setup-file)
                    (update :data cfo/set-tokens-source (:id library))
                    (update-in [:data :tokens-status]
                               ctos/set-tokens-status #{} #{(cthi/id :library-set)}))
        store   (ths/setup-store file)]
    (ptk/emit! store
               (dwtc/initialize (:id file))
               (dwtc/check-page {:opening? true}))
    (await (async/settle))
    (t/is (= "#7b2222" (fill-color store)) "without the library there are no tokens to set")

    ;; The workspace loads the libraries after the page opens
    (ptk/emit! store
               #(assoc-in % [:files (:id library)] library)
               (ptk/data-event :app.main.data.workspace/all-libraries-resolved {:file-id (:id file)}))
    (await (async/wait-for #(= "#00ff00" (fill-color store))
                           "the page is set once the library arrives"
                           {:max-ticks 300}))
    (t/is (= "#00ff00" (fill-color store)))

    (ptk/emit! store (dwtc/finalize))
    (rx/dispose! store)))

;; --- Lazy propagation of library tokens

(defn- setup-file-with-pages
  "`setup-file` with a second page holding a rectangle and a main component
  that apply `red`. The first page stays the current one."
  []
  (let [file          (setup-file)
        first-page-id (cthf/current-page-id file)]
    (-> file
        (cthf/add-sample-page :page2)
        (ctho/add-rect :rect2)
        (ctht/apply-token-to-shape :rect2 "red" [:fill] [:fill] "#7b2222")
        (ctho/add-simple-component :component1 :main-root :main-child)
        (ctht/apply-token-to-shape :main-root "red" [:fill] [:fill] "#7b2222")
        (vary-meta assoc :current-page-id first-page-id))))

(defn- page2-fill
  [store label]
  (let [file (ths/get-file-from-state @store)]
    (-> (cths/get-shape file label :page-label :page2) :fills first :fill-color)))

(defn- page2-read-fill
  "Fill of `label` on the second page as a reader sets it, with the resolved
  values saved in the file."
  [store label]
  (let [data    (:data (ths/get-file-from-state @store))
        page    (dsh/get-page data (cthi/id :page2))
        objects (cltp/correct-objects page
                                      (cltp/values->resolved-tokens (:tokens-resolved data))
                                      {})]
    (-> (get objects (cthi/id label)) :fills first :fill-color)))

(t/deftest ^:async lazy-library-change-then-flag-off
  (let [library (setup-library)
        file    (setup-file-with-pages)
        store   (ths/setup-store file {:libraries [library]})
        flags   cf/flags
        token   (ctob/get-token (ctht/get-tokens-lib library) (cthi/id :library-set) (cthi/id :library-red))
        changes (-> (pcb/empty-changes nil)
                    (pcb/with-library-data (:data library))
                    (pcb/set-token (cthi/id :library-set) (cthi/id :library-red)
                                   (assoc token :value "#0000ff")))]
    (await (use-library-tokens store library))
    (await (async/wait-for #(= "#00ff00" (page2-fill store :rect2))
                           "without the flag every page is set" {:max-ticks 200}))

    (set! cf/flags (conj flags :lazy-token-propagation))
    (ptk/emit! store (dwl/ext-library-changed (:id library) nil 1 (:redo-changes changes)))
    (await (async/wait-for #(= "#0000ff" (fill-color store))
                           "the current page is set"
                           {:max-ticks 300}))

    (t/testing "a reader sees the other page set"
      (await (async/wait-for #(= "#0000ff" (page2-read-fill store :rect2))
                             "the resolved values are saved"
                             {:max-ticks 300}))
      (t/is (= "#0000ff" (page2-read-fill store :rect2))))

    (t/is (= "#00ff00" (page2-fill store :rect2)) "the other pages wait")
    (t/is (= "#00ff00" (page2-fill store :main-root)) "their main components too")

    (set! cf/flags flags)
    (ptk/emit! store
               #(assoc % :current-page-id (cthi/id :page2))
               (dwtc/check-page {:opening? true}))
    (await (async/wait-for #(= "#0000ff" (page2-fill store :rect2))
                           "with the flag off again, opening the page sets it"
                           {:max-ticks 300}))
    (t/is (= "#0000ff" (page2-fill store :rect2)))

    (rx/dispose! store)))
