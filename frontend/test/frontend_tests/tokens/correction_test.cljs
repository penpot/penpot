;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.correction-test
  (:require
   [app.common.files.changes-builder :as pcb]
   [app.common.test-helpers.components :as cthc]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.tokens-lib :as ctob]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.correction :as dwtc]
   [app.main.data.workspace.tokens.leader :as leader]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.progress :as dwtpr]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.data.workspace.wasm-text :as dwwt]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [frontend-tests.tokens.helpers.propagation :as tohp]
   [frontend-tests.tokens.helpers.state :as tohs]
   [frontend-tests.tokens.helpers.tokens :as toht]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(t/deftest ^:async a-page-without-tokens-is-ready-when-it-opens
  (let [store (ths/setup-store (cthf/sample-file :file))
        ready (atom false)]
    (rx/subs! #(reset! ready true)
              (rx/filter (ptk/type? ::dwtc/tokens-ready) (ptk/input-stream store)))
    (ptk/emit! store (dwtc/check-page {:opening? true}))
    (await (async/wait-for-store store #(deref ready) "the page is ready"))
    (t/is @ready)))

(t/deftest editors-set-the-page-when-it-opens
  (t/async
    done
    (let [store   (ths/setup-store (tohp/setup-file))
          commits (tohs/watch-commits store)]
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [state]
         (let [file' (ths/get-file-from-state state)
               commit (first @commits)]
           (t/is (= 50 (:r1 (cths/get-shape file' :stale))))
           (t/is (= 50 (:r1 (cths/get-shape file' :legacy))) "unassigned values are set by editors")
           (t/is (= :local (:source commit)))
           (t/is (false? (:save-undo? commit)) "corrections are not undoable")
           (t/is (contains? (:tags commit) :token-propagation))))
       tohp/stop-on-ready))))

(t/deftest ^:async a-long-correction-blocks-the-workspace-first
  (await
   (mock/with-mocks* {dwtpr/long-weight 1}
     (let [store  (ths/setup-store (tohp/setup-file))
           events (atom [])]
       (rx/subs! #(cond
                    (ptk/type? ::dwtpr/show %)     (swap! events conj :show)
                    (dch/commit? %)                (swap! events conj :commit)
                    (ptk/type? ::dwtpr/add-work %)  (swap! events conj [:add (deref %)])
                    (ptk/type? ::dwtpr/work-done %) (swap! events conj [:done (deref %)]))
                 (ptk/input-stream store))
       (let [state (await (tohs/run-store* store [(dwtc/check-page {:opening? true})] tohp/stop-on-ready))]
         (t/is (= 50 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale))))
         (t/is (= [[:add 1] :show] (take 2 (remove #{:commit} @events)))
               "the dialog shows before the values are set")
         (t/is (= [:done 1] (last @events))))))))

(t/deftest readers-set-the-values-in-memory
  (t/async
    done
    (let [store   (ths/setup-store (tohp/setup-file))
          _       (swap! store assoc :permissions {:can-edit false})
          commits (tohs/watch-commits store)]
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (t/is (= 50 (:r1 (cths/get-shape file' :stale))))
           (t/is (= 10 (:r1 (cths/get-shape file' :legacy))) "readers trust unassigned values")
           (t/is (every? #(= :in-memory (:source %)) @commits) "nothing is saved")))
       tohp/stop-on-ready))))

(defn- answer-from-another-session
  "Set the radius of `id` back to 10, as a session that resolves the token
  to 10 would."
  [id]
  (ptk/reify ::answer-from-another-session
    ptk/UpdateEvent
    (update [_ state]
      (let [file-id (:current-file-id state)
            page-id (:current-page-id state)]
        (update-in state [:files file-id :data :pages-index page-id :objects id]
                   assoc :r1 10 :token-values {:r1 10})))))

(defn- after-ready
  "Emit `events` once the page holds its token values."
  [& events]
  (ptk/reify ::after-ready
    ptk/WatchEvent
    (watch [_ _ stream]
      (->> stream
           (rx/filter (ptk/type? ::dwtc/tokens-ready))
           (rx/take 1)
           (rx/mapcat #(rx/from events))))))

(t/deftest a-session-sets-a-value-once
  (t/async
    done
    (let [store   (ths/setup-store (tohp/setup-file))
          commits (tohs/watch-commits store)
          stale   (cthi/id :stale)]
      (tohs/run-store
       store done [(dwtc/check-page {:ids [stale]})
                   (after-ready (answer-from-another-session stale)
                                (dwtc/check-page {:ids [stale]}))]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (t/is (= 10 (:r1 (cths/get-shape file' :stale))) "this session doesn't answer again")
           (t/is (= 1 (count (filter (fn [commit] (some #(= :mod-obj (:type %)) (:redo-changes commit)))
                                     @commits))))))
       (fn [stream]
         (->> stream
              (rx/filter (ptk/type? ::dwtc/tokens-ready))
              (rx/take 2)
              (rx/last)))))))

(defn- lay-out-again
  "Commit of the layout of `id` of `page-id` after a change, as the texts
  get."
  [page-id id]
  (dch/commit-changes {:redo-changes [{:type :mod-obj :id id :page-id page-id
                                       :operations [{:type :set :attr :name :val "laid out"}]}]
                       :undo-changes []
                       :save-undo? false
                       :tags #{:position-data}}))

(t/deftest a-session-sets-a-value-once-also-when-laid-out-again
  (t/async
    done
    (let [file    (tohp/setup-file)
          store   (ths/setup-store file)
          commits (tohs/watch-commits store)
          stale   (cthi/id :stale)]
      (tohs/run-store
       store done [(dwtc/initialize (:id file))
                   (dwtc/check-page {:ids [stale]})
                   (after-ready (lay-out-again (cthf/current-page-id file) stale)
                                (answer-from-another-session stale)
                                (dwtc/check-page {:ids [stale]}))]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (t/is (= 10 (:r1 (cths/get-shape file' :stale))) "this session doesn't answer again")
           (t/is (= 1 (count (filter (fn [commit]
                                       (and (contains? (:tags commit) :token-propagation)
                                            (some #(= :mod-obj (:type %)) (:redo-changes commit))))
                                     @commits))))))
       (fn [stream]
         (->> stream
              (rx/filter (ptk/type? ::dwtc/tokens-ready))
              (rx/take 2)
              (rx/last)))))))

(defn- with-two-editors
  "Store of `file` for this session and another editor on the current page;
  `first?` when this session goes first."
  [file first?]
  (let [[low high] [#uuid "00000000-0000-0000-0000-000000000001"
                    #uuid "ffffffff-ffff-ffff-ffff-ffffffffffff"]
        [this other] (if first? [low high] [high low])
        store        (ths/setup-store file)]
    (swap! store assoc
           :session-id this
           :workspace-presence {other {:can-edit true :page-id (cthf/current-page-id file)}})
    store))

(defn- watch-event
  "Atom with the times `event`, a keyword, goes through `store`."
  [store event]
  (let [events (atom [])]
    (rx/subs! #(swap! events conj %)
              (rx/filter #(= event %) (ptk/input-stream store)))
    events))

(t/deftest a-waiting-session-saves-nothing-of-a-remote-change
  (t/async
    done
    (let [store   (with-two-editors (tohp/setup-file) false)
          commits (tohs/watch-commits store)]
      (tohs/run-store
       store done [(dwtc/check-page {:remote? true})]
       (fn [_]
         (t/is (empty? @commits) "neither the shapes nor the resolved values"))
       (tohs/stop-on ::dwtc/set-out-of-date)))))

(t/deftest the-first-session-sends-a-remote-change-at-once
  (t/async
    done
    (let [store    (with-two-editors (tohp/setup-file) true)
          commits  (tohs/watch-commits store)
          persists (watch-event store :app.main.data.persistence/force-persist)]
      (tohs/run-store
       store done [(dwtc/check-page {:remote? true})]
       (fn [state]
         (t/is (= 50 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale))))
         (t/is (some (fn [commit] (some #(= :set-tokens-resolved (:type %)) (:redo-changes commit))) @commits))
         (t/is (= 1 (count @persists)) "the others wait for these values"))
       tohp/stop-on-ready))))

(t/deftest ^:async the-tokens-are-resolved-again-only-when-they-change
  (let [calls   (atom 0)
        resolve dwta/resolve-sets]
    (await
     (mock/with-mocks*
       {dwta/resolve-sets (fn [sets base-font-size] (swap! calls inc) (resolve sets base-font-size))}
       (let [store (ths/setup-store (tohp/setup-file))]
         (await (tohs/run-store*
                 store [(dwtc/check-page {:opening? true})
                        ;; another check once the first one is done
                        (ptk/reify ::check-again
                          ptk/WatchEvent
                          (watch [_ _ stream]
                            (->> stream
                                 (rx/filter (ptk/type? ::dwtc/tokens-ready))
                                 (rx/take 1)
                                 (rx/map (fn [_] (dwtc/check-page {:opening? true}))))))]
                 (fn [stream]
                   (->> stream
                        (rx/filter (ptk/type? ::dwtc/tokens-ready))
                        (rx/take 2)
                        (rx/last)))))
         (t/is (= 1 @calls)))))))

(t/deftest a-change-of-the-tokens-is-checked
  (t/async
    done
    (let [file    (tohp/setup-file)
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          ;; as an undo commits it: no propagation of its own
          changes (-> (pcb/empty-changes nil)
                      (pcb/with-library-data (:data file))
                      (pcb/set-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70)))]
      (tohs/run-store
       store done [(dwtc/initialize (:id file))
                   (dwtc/check-page {:opening? true})
                   (dch/commit-changes changes)]
       (fn [state]
         (t/is (= 70 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale)))))
       (fn [stream]
         (->> stream
              (rx/filter (ptk/type? ::dwtc/tokens-ready))
              (rx/take 2)
              (rx/last)))))))

(t/deftest the-values-wait-for-the-renderer-to-hold-the-page
  (t/async
    done
    (let [file    (tohp/setup-file)
          page-id (cthf/current-page-id file)
          store   (ths/setup-store file)
          _       (swap! store assoc :features #{"render-wasm/v1"})
          commits (tohs/watch-commits store)
          before  (atom nil)
          ;; the renderer loads the shapes of the page a while after it opens
          loaded  (ptk/reify ::loaded
                    ptk/WatchEvent
                    (watch [_ _ _]
                      (->> (rx/of (dw/page-shapes-loaded page-id))
                           (rx/delay 100)
                           (rx/tap #(reset! before (count @commits))))))]
      (tohs/run-store
       store done [(dwtc/initialize (:id file))
                   (ptk/data-event :app.main.data.workspace.pages/initialized page-id)
                   loaded]
       (fn [state]
         (t/is (= 0 @before) "nothing is set before the shapes are loaded")
         (t/is (= 50 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale)))))
       (fn [stream]
         (->> stream
              (rx/filter (ptk/type? ::dwtc/tokens-ready))
              (rx/take 1)))))))

(defn- setup-text-file
  "File with the token `size` (font size 20) applied to the text `text`."
  []
  (-> (toht/sample-file-with-tokens [{:label :size :name "size" :type :font-size :value 20}])
      (ctho/add-frame-with-text :frame :text "hello")
      ;; holds the value of a previous version of the token
      (ctht/apply-token-to-shape :text "size" [:font-size] [:font-size] "14")
      (cths/update-shape :text :token-values {:font-size 14})))

(t/deftest ^:async the-text-resize-of-a-correction-is-not-an-edit
  (let [calls (atom [])]
    (await
     (mock/with-mocks*
       {dwwt/resize-wasm-text-all (fn
                                    ([ids] (ptk/data-event ::resized ids))
                                    ([ids opts]
                                     (swap! calls conj opts)
                                     (ptk/data-event ::resized ids)))}
       (let [store (ths/setup-store (setup-text-file))]
         (await (tohs/run-store* store [(dwtc/check-page {:opening? true})] tohp/stop-on-ready))
         (t/is (= [{:skip-component-sync? true :save-undo? false :ignore-save-lock true}] @calls)
               "the copies get their values from the tokens, nothing is undoable and it follows them while saves fail"))))))

(t/deftest ^:async the-texts-are-resized-in-the-pass-of-the-sizes
  (let [resized (atom 0)
        passes  (atom [])]
    (await
     (mock/with-mocks*
       {cf/flags                          (conj cf/flags :lazy-token-propagation)
        dwwt/resize-wasm-text-all         (fn
                                            ([ids] (swap! resized inc) (ptk/data-event ::resized ids))
                                            ([ids _] (swap! resized inc) (ptk/data-event ::resized ids)))
        dwwt/resize-wasm-text-modifiers   (fn
                                            ([shape] {(:id shape) {:modifiers :measured}})
                                            ([shape _] {(:id shape) {:modifiers :measured}}))
        dwwt/content-fonts-ready?         (constantly true)
        dwm/apply-wasm-modifiers          (fn [tree & {:as opts}]
                                            (swap! passes conj [tree (:save-undo? opts)])
                                            (ptk/data-event ::geometry tree))}
       (let [store   (ths/setup-store (setup-text-file))
             text-id (cthi/id :text)]
         (await (tohs/run-store* store [(dwtc/check-page {:opening? true})] tohp/stop-on-ready))
         (t/is (= 0 @resized) "no second pass")
         (t/is (= [[{text-id {:modifiers :measured}} false]] @passes)
               "the text gets its measured size in a single pass, not undoable"))))))

(t/deftest only-the-texts-the-renderer-resizes-wait-for-it
  (t/async
    done
    (let [file    (-> (toht/sample-file-with-tokens
                       [{:label :color :name "color" :type :color :value "#ff0000"}
                        {:label :size :name "size" :type :font-size :value 20}])
                      ;; both hold the value of a previous version of their token
                      (ctho/add-frame-with-text :colored-frame :colored "hello")
                      (ctht/apply-token-to-shape :colored "color" [:fill] [] nil)
                      (cths/update-shape :colored :token-values {:fill "#00ff00"})
                      (ctho/add-frame-with-text :sized-frame :sized "hello")
                      (ctht/apply-token-to-shape :sized "size" [:font-size] [:font-size] "14")
                      (cths/update-shape :sized :token-values {:font-size 14}))
          store   (ths/setup-store file)
          reflows (atom #{})]
      (rx/subs! #(swap! reflows into (:ids (deref %)))
                (rx/filter (ptk/type? :text/reflow) (ptk/input-stream store)))
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (t/is (= {:fill "#ff0000" :font-size 20}
                    (merge (:token-values (cths/get-shape file' :colored))
                           (:token-values (cths/get-shape file' :sized))))
                 "both texts are set")
           (t/is (= #{(cthi/id :sized)} @reflows)
                 "a new color leaves the size of the text as it is")))
       tohp/stop-on-ready))))

;; --- Values for the readers

(t/deftest editors-save-the-resolved-values
  (t/async
    done
    (let [store (ths/setup-store (tohp/setup-file))]
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [state]
         (t/is (= {"radius" 50}
                  (:tokens-resolved (:data (ths/get-file-from-state state))))))
       tohp/stop-on-ready))))

;; --- Main components with out of date values

(defn- sample-file-with-radius
  "File `label` whose active token `radius` is `value`."
  [label value & {:as params}]
  (toht/sample-file-with-tokens [{:name "radius" :type :border-radius :value value}]
                                (assoc params :file-id label)))

(defn- with-radius
  "Apply the token `radius` to the radius `r1` of `label`, set to `value`."
  [file label value]
  (-> file
      (ctht/apply-token-to-shape label "radius" [:r1] [:r1] value)
      (cths/update-shape label :token-values {:r1 value})))

(defn- copy-values
  "The `r1` values that the commits of `commits` write in the shape `id`."
  [commits id]
  (into []
        (comp (mapcat :redo-changes)
              (filter #(= id (:id %)))
              (mapcat :operations)
              (keep #(get-in % [:values :r1])))
        commits))

(defn- run-correction
  "Run `events` with the correction of `file` on, and call `check` when the
  correction is done with the page twice: when it opens and after `events`."
  [store done file events check]
  (tohs/run-store
   store done (into [(dwtc/initialize (:id file))
                     (dwtc/check-page {:opening? true})]
                    events)
   check
   (fn [stream]
     (->> stream
          (rx/filter (ptk/type? ::dwtc/tokens-ready))
          (rx/take 2)
          (rx/last)))))

(t/deftest a-copy-synced-from-an-out-of-date-library-main-is-set-again
  (t/async
    done
    (let [;; the main holds the value of a previous version of the token
          library  (-> (sample-file-with-radius :library 50 :is-shared true)
                       (ctho/add-simple-component :component1 :main-root :main-child)
                       (with-radius :main-root 10))
          consumer (-> (sample-file-with-radius :consumer 50)
                       (cthc/instantiate-component :component1 :copy-root :library library)
                       (with-radius :copy-root 50))
          store    (ths/setup-store consumer {:libraries [library]})
          commits  (tohs/watch-commits store)
          copy-id  (cthi/id :copy-root)]
      (run-correction
       store done consumer [(dwl/sync-file (:id consumer) (:id library))]
       (fn [state]
         (let [copy (cths/get-shape (ths/get-file-from-state state) :copy-root)]
           (t/is (= [10 50] (copy-values @commits copy-id))
                 "the sync brings the value of the main, the correction sets it again")
           (t/is (= 50 (:r1 copy)))
           (t/is (= {:r1 50} (:token-values copy)))))))))

(t/deftest a-copy-reset-from-an-out-of-date-main-is-set-again
  (t/async
    done
    (let [file    (sample-file-with-radius :file1 50)
          page1   (cthf/current-page-id file)
          ;; the main, on another page, holds a previous value of the token
          file    (-> file
                      (cthf/add-sample-page :page2)
                      (ctho/add-simple-component :component1 :main-root :main-child)
                      (with-radius :main-root 10)
                      (vary-meta assoc :current-page-id page1)
                      (cthc/instantiate-component :component1 :copy-root)
                      (with-radius :copy-root 50))
          store   (ths/setup-store file)
          commits (tohs/watch-commits store)
          copy-id (cthi/id :copy-root)]
      (run-correction
       store done file [(dwl/reset-component copy-id)]
       (fn [state]
         (let [copy (cths/get-shape (ths/get-file-from-state state) :copy-root)]
           (t/is (= [10 50] (copy-values @commits copy-id))
                 "the reset brings the value of the main, the correction sets it again")
           (t/is (= 50 (:r1 copy)))
           (t/is (= {:r1 50} (:token-values copy)))))))))

;; --- Failures

(t/deftest ^:async a-failed-correction-still-ends-its-work
  (await
   (mock/with-mocks* {dwta/commit-token-values (fn [& _] (rx/throw (ex-info "failed" {})))}
     (let [store  (ths/setup-store (tohp/setup-file))
           events (atom [])]
       (rx/subs! #(cond
                    (ptk/type? ::dwtpr/add-work %)  (swap! events conj [:add (deref %)])
                    (ptk/type? ::dwtpr/work-done %) (swap! events conj [:done (deref %)]))
                 (ptk/input-stream store))
       (let [state (await (tohs/run-store* store [(dwtc/check-page {:opening? true})] tohp/stop-on-ready))]
         (t/is (= [[:add 1] [:done 1]] @events) "the dialog closes")
         (t/is (empty? (::dwtc/guard state)) "the shapes are not taken as set"))))))

;; Scenario: with lazy changes, the token goes 50 -> 70 -> 80 -> 70 and the
;; second page is opened after each change, so this session sets it each
;; time. Proves: the value it set before is set again.
(t/deftest ^:async a-value-set-before-is-set-again-after-a-change-of-the-tokens
  (await
   (mock/with-mocks* {cf/flags (tohp/lazy-flags-without-drain)}
     (let [file   (tohp/setup-file-with-pages)
           store  (ths/setup-store file)
           page1  (cthf/current-page-id file)
           page2  (cthi/id :page2)
           token  (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
           radius #(get-in @store [:files (:id file) :data :pages-index %2 :objects (cthi/id %1) :r1])]
       (ptk/emit! store (dwtc/initialize (:id file)))
       (doseq [value [70 80 70]]
         (ptk/emit! store
                    #(assoc % :current-page-id page1)
                    (dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value value)))
         (await (async/wait-for-store store #(and (= value (radius :stale page1))
                                                  (empty? (::dwtp/running @store)))
                                      "the current page is set"))
         (ptk/emit! store
                    #(assoc % :current-page-id page2)
                    (dwtc/check-page {:opening? true}))
         (await (async/wait-for-store store #(= value (radius :other page2))
                                      (str "the second page is set to " value))))
       (t/is (= 70 (radius :other page2)))
       (ptk/emit! store (dwtc/finalize))
       (rx/dispose! store)))))

;; Scenario: the workspace is loaded again, which starts its watchers again
;; without a finalize, and then a shape is edited. Proves: the page is
;; checked once, and the dialog is hidden once.
(t/deftest ^:async a-reload-keeps-one-watcher-of-the-commits
  (let [file   (tohp/setup-file)
        store  (ths/setup-store file)
        events (atom [])]
    (rx/subs! #(swap! events conj (ptk/type %))
              (rx/filter #(or (ptk/type? ::dwtc/check-page %) (ptk/type? ::dwtpr/hide %))
                         (ptk/input-stream store)))
    (ptk/emit! store
               (dwtc/initialize (:id file)) (dwtpr/initialize)
               (dwtc/initialize (:id file)) (dwtpr/initialize)
               (dch/commit-changes {:redo-changes [{:type :mod-obj :id (cthi/id :stale)
                                                    :page-id (cthf/current-page-id file)
                                                    :operations [{:type :set :attr :name :val "edited"}]}]
                                    :undo-changes []
                                    :save-undo? false})
               (dwtpr/add-work 1) (dwtpr/show) (dwtpr/work-done 1))
    (await (async/wait-for-store store #(some #{::dwtpr/hide} @events) "the dialog is hidden"))
    (await (async/wait-for-store store #(some #{::dwtc/check-page} @events) "the page is checked"))
    ;; A second watcher would check the page and hide the dialog in the same settle
    (await (js/Promise. (fn [resolve] (js/setTimeout resolve leader/settle-ms))))
    (await (async/settle))
    (t/is (= 1 (count (filter #{::dwtc/check-page} @events))) "the page is checked once")
    (t/is (= 1 (count (filter #{::dwtpr/hide} @events))) "the dialog is hidden once")
    (ptk/emit! store (dwtc/finalize) (dwtpr/finalize))
    (rx/dispose! store)))
