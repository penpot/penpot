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
   [app.common.types.tokens-status :as ctos]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.modifiers :as dwm]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.correction :as dwtc]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.progress :as dwtpr]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.data.workspace.wasm-text :as dwwt]
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

(defn- watch-commits
  [store]
  (let [commits (atom [])]
    (rx/subs! #(swap! commits conj (deref %))
              (rx/filter dch/commit? (ptk/input-stream store)))
    commits))

(def ^:private stop-on-ready
  (tohs/stop-on ::dwtc/tokens-ready))

(t/deftest a-page-without-tokens-is-ready-when-it-opens
  (t/async
    done
    (let [store (ths/setup-store (cthf/sample-file :file))
          ready (atom false)]
      (rx/subs! #(reset! ready true)
                (rx/filter (ptk/type? ::dwtc/tokens-ready) (ptk/input-stream store)))
      (ptk/emit! store (dwtc/check-page {:opening? true}))
      (js/setTimeout #(do (t/is @ready) (done)) 100))))

(t/deftest editors-set-the-page-when-it-opens
  (t/async
    done
    (let [store   (ths/setup-store (tohp/setup-file))
          commits (watch-commits store)]
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
       stop-on-ready))))

(t/deftest a-long-correction-blocks-the-workspace-first
  (t/async
    done
    (let [weight dwtpr/long-weight
          _      (set! dwtpr/long-weight 1)
          store  (ths/setup-store (tohp/setup-file))
          events (atom [])]
      (rx/subs! #(cond
                   (ptk/type? ::dwtpr/show %)     (swap! events conj :show)
                   (dch/commit? %)                (swap! events conj :commit)
                   (ptk/type? ::dwtpr/add-work %)  (swap! events conj [:add (deref %)])
                   (ptk/type? ::dwtpr/work-done %) (swap! events conj [:done (deref %)]))
                (ptk/input-stream store))
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [state]
         (set! dwtpr/long-weight weight)
         (t/is (= 50 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale))))
         (t/is (= [[:add 1] :show] (take 2 (remove #{:commit} @events)))
               "the dialog shows before the values are set")
         (t/is (= [:done 1] (last @events))))
       stop-on-ready))))

(t/deftest readers-set-the-values-in-memory
  (t/async
    done
    (let [store   (ths/setup-store (tohp/setup-file))
          _       (swap! store assoc :permissions {:can-edit false})
          commits (watch-commits store)]
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [state]
         (let [file' (ths/get-file-from-state state)]
           (t/is (= 50 (:r1 (cths/get-shape file' :stale))))
           (t/is (= 10 (:r1 (cths/get-shape file' :legacy))) "readers trust unassigned values")
           (t/is (every? #(= :in-memory (:source %)) @commits) "nothing is saved")))
       stop-on-ready))))

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
          commits (watch-commits store)
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
          commits (watch-commits store)
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
              (rx/take 1)
              (rx/delay 300)))))))

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
          commits (watch-commits store)]
      (tohs/run-store
       store done [(dwtc/check-page {:remote? true})]
       (fn [_]
         (t/is (empty? @commits) "neither the shapes nor the resolved values"))
       (tohs/stop-on ::dwtc/set-out-of-date)))))

(t/deftest the-first-session-sends-a-remote-change-at-once
  (t/async
    done
    (let [store    (with-two-editors (tohp/setup-file) true)
          commits  (watch-commits store)
          persists (watch-event store :app.main.data.persistence/force-persist)]
      (tohs/run-store
       store done [(dwtc/check-page {:remote? true})]
       (fn [state]
         (t/is (= 50 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale))))
         (t/is (some (fn [commit] (some #(= :set-tokens-resolved (:type %)) (:redo-changes commit))) @commits))
         (t/is (= 1 (count @persists)) "the others wait for these values"))
       stop-on-ready))))

(t/deftest the-tokens-are-resolved-again-only-when-they-change
  (t/async
    done
    (let [calls   (atom 0)
          resolve dwta/resolve-sets
          store   (ths/setup-store (tohp/setup-file))]
      (set! dwta/resolve-sets
            (fn [sets base-font-size] (swap! calls inc) (resolve sets base-font-size)))
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})
                   ;; another check once the first one is done
                   (ptk/reify ::check-again
                     ptk/WatchEvent
                     (watch [_ _ stream]
                       (->> stream
                            (rx/filter (ptk/type? ::dwtc/tokens-ready))
                            (rx/take 1)
                            (rx/map (fn [_] (dwtc/check-page {:opening? true}))))))]
       (fn [_]
         (set! dwta/resolve-sets resolve)
         (t/is (= 1 @calls)))
       (fn [stream]
         (->> stream
              (rx/filter (ptk/type? ::dwtc/tokens-ready))
              (rx/take 2)
              (rx/last)))))))

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
          commits (watch-commits store)
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

(t/deftest the-text-resize-of-a-correction-is-not-an-edit
  (t/async
    done
    (let [calls   (atom [])
          resize  dwwt/resize-wasm-text-all
          file    (-> (ctht/sample-file-with-tokens
                       :lib-fn #(-> %
                                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set) :name "set"))
                                    (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :theme)
                                                                           :name "theme"
                                                                           :sets #{"set"}))
                                    (ctob/add-token (cthi/id :set)
                                                    (ctob/make-token :id (cthi/new-id! :size)
                                                                     :name "size"
                                                                     :type :font-size
                                                                     :value 20)))
                       :status-fn #(ctos/set-tokens-status % #{(cthi/id :theme)} #{(cthi/id :set)}))
                      (ctho/add-frame-with-text :frame :text "hello")
                      ;; holds the value of a previous version of the token
                      (ctht/apply-token-to-shape :text "size" [:font-size] [:font-size] "14")
                      (cths/update-shape :text :token-values {:font-size 14}))
          store   (ths/setup-store file)]
      (set! dwwt/resize-wasm-text-all
            (fn
              ([ids] (ptk/data-event ::resized ids))
              ([ids opts]
               (swap! calls conj opts)
               (ptk/data-event ::resized ids))))
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [_]
         (set! dwwt/resize-wasm-text-all resize)
         (t/is (= [{:skip-component-sync? true :save-undo? false}] @calls)
               "the copies get their values from the tokens and nothing is undoable"))
       stop-on-ready))))

(t/deftest the-texts-are-resized-in-the-pass-of-the-sizes
  (t/async
    done
    (let [flags    cf/flags
          resize   dwwt/resize-wasm-text-all
          measure  dwwt/resize-wasm-text-modifiers
          ready?   dwwt/content-fonts-ready?
          apply    dwm/apply-wasm-modifiers
          resized  (atom 0)
          passes   (atom [])
          file     (-> (ctht/sample-file-with-tokens
                        :lib-fn #(-> %
                                     (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set) :name "set"))
                                     (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :theme)
                                                                            :name "theme"
                                                                            :sets #{"set"}))
                                     (ctob/add-token (cthi/id :set)
                                                     (ctob/make-token :id (cthi/new-id! :size)
                                                                      :name "size"
                                                                      :type :font-size
                                                                      :value 20)))
                        :status-fn #(ctos/set-tokens-status % #{(cthi/id :theme)} #{(cthi/id :set)}))
                       (ctho/add-frame-with-text :frame :text "hello")
                       ;; holds the value of a previous version of the token
                       (ctht/apply-token-to-shape :text "size" [:font-size] [:font-size] "14")
                       (cths/update-shape :text :token-values {:font-size 14}))
          text-id  (cthi/id :text)
          store    (ths/setup-store file)
          restore  (fn []
                     (set! cf/flags flags)
                     (set! dwwt/resize-wasm-text-all resize)
                     (set! dwwt/resize-wasm-text-modifiers measure)
                     (set! dwwt/content-fonts-ready? ready?)
                     (set! dwm/apply-wasm-modifiers apply))]
      (set! cf/flags (conj flags :lazy-token-propagation))
      (set! dwwt/resize-wasm-text-all
            (fn
              ([ids] (swap! resized inc) (ptk/data-event ::resized ids))
              ([ids _] (swap! resized inc) (ptk/data-event ::resized ids))))
      (set! dwwt/resize-wasm-text-modifiers
            (fn
              ([shape] {(:id shape) {:modifiers :measured}})
              ([shape _] {(:id shape) {:modifiers :measured}})))
      (set! dwwt/content-fonts-ready? (constantly true))
      (set! dwm/apply-wasm-modifiers
            (fn [tree & {:as opts}]
              (swap! passes conj [tree (:save-undo? opts)])
              (ptk/data-event ::geometry tree)))
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [_]
         (restore)
         (t/is (= 0 @resized) "no second pass")
         (t/is (= [[{text-id {:modifiers :measured}} false]] @passes)
               "the text gets its measured size in a single pass, not undoable"))
       stop-on-ready))))

(t/deftest only-the-texts-the-renderer-resizes-wait-for-it
  (t/async
    done
    (let [file    (-> (ctht/sample-file-with-tokens
                       :lib-fn #(-> %
                                    (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set) :name "set"))
                                    (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :theme)
                                                                           :name "theme"
                                                                           :sets #{"set"}))
                                    (ctob/add-token (cthi/id :set)
                                                    (ctob/make-token :id (cthi/new-id! :color)
                                                                     :name "color"
                                                                     :type :color
                                                                     :value "#ff0000"))
                                    (ctob/add-token (cthi/id :set)
                                                    (ctob/make-token :id (cthi/new-id! :size)
                                                                     :name "size"
                                                                     :type :font-size
                                                                     :value 20)))
                       :status-fn #(ctos/set-tokens-status % #{(cthi/id :theme)} #{(cthi/id :set)}))
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
       stop-on-ready))))

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
       stop-on-ready))))

(t/deftest lazy-changes-drain-the-other-pages
  (t/async
    done
    (let [restore (tohp/with-lazy-flag)
          file    (tohp/setup-file-with-pages)
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          other   (cthi/id :other)
          commits (watch-commits store)]
      (tohs/run-store
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [state]
         (restore)
         (t/is (= 70 (:r1 (cths/get-shape (ths/get-file-from-state state) :other :page-label :page2)))
               "the other page is set in the background")
         (t/is (false? (:save-undo? (last @commits))) "not undoable"))
       (fn [stream]
         (->> stream
              (rx/filter dch/commit?)
              (rx/filter #(some (fn [change] (= other (:id change))) (:redo-changes (deref %))))
              (rx/take 1)
              (rx/delay 10)))))))

(t/deftest the-drain-saves-the-change-and-each-page-at-once
  (t/async
    done
    (let [pause   dwtp/drain-delay-ms
          _       (set! dwtp/drain-delay-ms 1)
          restore #(set! dwtp/drain-delay-ms pause)
          stream  (rx/subject)
          events  (atom [])
          persist :app.main.data.persistence/force-persist
          drain   (#'dwtp/drain-pages {} {} [(uuid/next) (uuid/next)])]
      (rx/subs!
       (fn [event]
         (swap! events conj (if (= persist event) :persist :page))
         (when (ptk/type? ::dwtp/drain-scope event)
           ;; the page is set
           (rx/push! stream (ptk/data-event ::dwtp/page-done {:scope-id (deref event)}))))
       (fn [cause] (restore) (t/is (nil? cause)) (done))
       (fn []
         (restore)
         (t/is (= [:persist :page :persist :page :persist] @events)
               "the change before the drain, and each page right after it is set")
         (done))
       (ptk/watch drain {} stream)))))

(t/deftest the-drain-stops-when-the-tokens-change
  (t/async
    done
    (let [pause   dwtp/drain-delay-ms
          _       (set! dwtp/drain-delay-ms 1)
          restore #(set! dwtp/drain-delay-ms pause)
          stream  (rx/subject)
          events  (atom [])
          persist :app.main.data.persistence/force-persist
          drain   (#'dwtp/drain-pages {} {} [(uuid/next) (uuid/next)])]
      (rx/subs!
       (fn [event]
         (swap! events conj (if (= persist event) :persist :page))
         (when (ptk/type? ::dwtp/drain-scope event)
           (rx/push! stream (ptk/data-event ::dwtp/page-done {:scope-id (deref event)}))
           ;; an undo of the change, once the first page is set
           (rx/push! stream (ptk/data-event ::dch/commit {:redo-changes [{:type :set-token}]}))))
       (fn [cause] (restore) (t/is (nil? cause)) (done))
       (fn []
         (restore)
         (t/is (= [:persist :page :persist] @events)
               "the second page is left for the editors and readers")
         (done))
       (ptk/watch drain {} stream)))))

;; --- Main components with out of date values

(defn- sample-file-with-radius
  "File `label` whose active token `radius` is `value`."
  [label value & {:as params}]
  (ctht/sample-file-with-tokens
   (assoc params
          :file-id label
          :lib-fn #(-> %
                       (ctob/add-set (ctob/make-token-set :id (cthi/new-id! :set) :name "set"))
                       (ctob/add-theme (ctob/make-token-theme :id (cthi/new-id! :theme)
                                                              :name "theme"
                                                              :sets #{"set"}))
                       (ctob/add-token (cthi/id :set)
                                       (ctob/make-token :name "radius"
                                                        :type :border-radius
                                                        :value value)))
          :status-fn #(ctos/set-tokens-status % #{(cthi/id :theme)} #{(cthi/id :set)}))))

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
          commits  (watch-commits store)
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
          commits (watch-commits store)
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

(t/deftest a-failed-correction-still-ends-its-work
  (t/async
    done
    (let [commit dwta/commit-token-values
          store  (ths/setup-store (tohp/setup-file))
          events (atom [])]
      (set! dwta/commit-token-values (fn [& _] (rx/throw (ex-info "failed" {}))))
      (rx/subs! #(cond
                   (ptk/type? ::dwtpr/add-work %)  (swap! events conj [:add (deref %)])
                   (ptk/type? ::dwtpr/work-done %) (swap! events conj [:done (deref %)]))
                (ptk/input-stream store))
      (tohs/run-store
       store done [(dwtc/check-page {:opening? true})]
       (fn [state]
         (set! dwta/commit-token-values commit)
         (t/is (= [[:add 1] [:done 1]] @events) "the dialog closes")
         (t/is (empty? (::dwtc/guard state)) "the shapes are not taken as set"))
       stop-on-ready))))
