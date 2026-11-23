;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.propagation-test
  "The propagation of a change of the tokens to the pages of the file
  (`app.main.data.workspace.tokens.propagation`)."
  (:require
   [app.common.logic.tokens-propagation :as cltp]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.thumbnails :as thc]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.correction :as dwtc]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.page-values :as pv]
   [app.main.data.workspace.tokens.progress :as dwtpr]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.data.workspace.tokens.remapping :as remap]
   [app.main.data.workspace.tokens.typography :as dwtt]
   [app.util.activity :as activity]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.mock :as mock]
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

(defn- after-propagations
  "Emit `events` once the running token propagation has finished."
  [& events]
  (ptk/reify ::after-propagations
    ptk/WatchEvent
    (watch [_ _ stream]
      (->> stream
           (rx/filter (ptk/type? :app.main.data.workspace.tokens.propagation/propagation-finished))
           (rx/take 1)
           (rx/mapcat #(rx/from events))))))

(t/deftest ^:async lazy-changes-set-only-the-current-page
  (await
   (mock/with-mocks* {cf/flags (tohp/lazy-flags-without-drain)}
     (let [file  (tohp/setup-file-with-pages)
           store (ths/setup-store file)
           token (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
           page2 #(cths/get-shape (ths/get-file-from-state %1) %2 :page-label :page2)
           state (await (tohs/run-store*
                         store [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
                         tohs/stop-on-send-update-indices))]
       (t/is (= 70 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale))) "the current page")
       (t/is (= 50 (:r1 (page2 state :other))) "the other pages wait")
       (t/is (= 50 (:r1 (page2 state :main-root))) "their main components too")))))

(t/deftest ^:async lazy-pages-are-set-when-opened
  (await
   (mock/with-mocks* {cf/flags (tohp/lazy-flags-without-drain)}
     (let [file  (tohp/setup-file-with-pages)
           store (ths/setup-store file)
           token (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
           page2 (cthi/id :page2)
           state (await (tohs/run-store*
                         store [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))
                                (after-propagations #(assoc % :current-page-id page2)
                                                    (dwtc/check-page {:opening? true}))]
                         (fn [stream]
                           (->> stream
                                (rx/filter #(and (ptk/type? ::dwtc/tokens-ready %)
                                                 (= page2 (:page-id (deref %)))))
                                (rx/take 1)))))]
       (t/is (= 70 (:r1 (cths/get-shape (ths/get-file-from-state state) :other :page-label :page2))))))))

(t/deftest ^:async a-new-base-font-size-sets-every-page
  (let [resolve dwta/resolve-sets]
    (await
     (mock/with-mocks*
       ;; The radius is a rem value: 50 at 16 pixels the rem
       {dwta/resolve-sets (fn [sets base-font-size]
                            (->> (resolve sets base-font-size)
                                 (rx/map (fn [tokens]
                                           (update-vals tokens #(update % :resolved-value * (/ base-font-size 16)))))))}
       (let [file    (assoc-in (tohp/setup-file-with-pages) [:data :options :base-font-size] "20px")
             store   (ths/setup-store file)
             commits (tohs/watch-commits store)
             page2   #(cths/get-shape (ths/get-file-from-state %1) %2 :page-label :page2)
             state   (await (tohs/run-store* store [(dwtt/set-base-font-size "32px")]
                                             tohs/stop-on-send-update-indices))]
         (t/is (= 100 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale))) "the current page")
         (t/is (= 100 (:r1 (page2 state :other))) "the other pages")
         (t/is (= 100 (:r1 (page2 state :main-root))))
         (t/is (= ["20px"] (into [] (comp (mapcat :undo-changes)
                                          (filter #(= :set-base-font-size (:type %)))
                                          (map :base-font-size))
                                 @commits))
               "undone to the size it had"))))))

(t/deftest a-remap-sets-every-page-in-one-undo-step
  (t/async
    done
    (let [;; The token was renamed and changed; the shapes still apply its old name
          file    (ctht/update-tokens-lib (tohp/setup-file-with-pages)
                                          #(ctob/update-token % (cthi/id :set) (cthi/id :radius)
                                                              (fn [token] (assoc token :name "corner" :value 70))))
          store   (ths/setup-store file)
          commits (tohs/watch-commits store)
          page2   #(cths/get-shape (ths/get-file-from-state %1) %2 :page-label :page2)]
      (tohs/run-store-async
       store done [(remap/remap-tokens "radius" "corner")]
       (fn [state]
         (t/is (= "corner" (get-in (page2 state :other) [:applied-tokens :r1])))
         (t/is (= 70 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale))) "the current page")
         (t/is (= 70 (:r1 (page2 state :other))) "the other pages")
         (t/is (= 70 (:r1 (page2 state :main-root))))
         (t/is (= 1 (count (into #{} (keep #(when (:save-undo? % true) (:undo-group %))) @commits)))
               "one undo step"))))))

(defn- commit-target
  "What `commit` sets: the tokens alone, the page `page1` or another page."
  [page1 commit]
  (let [page-ids (into #{} (keep :page-id) (:redo-changes commit))]
    (cond
      (empty? page-ids) :tokens
      (contains? page-ids page1) :current-page
      :else :other-page)))

(t/deftest ^:async the-change-is-saved-and-the-other-pages-are-not
  (await
   (mock/with-mocks* {cf/flags (tohp/lazy-flags-without-drain)}
     (let [file   (tohp/setup-file-with-pages)
           store  (ths/setup-store file)
           token  (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
           page1  (cthf/current-page-id file)
           events (atom [])]
       (rx/subs! (fn [event]
                   (cond
                     (= :app.main.data.persistence/force-persist event)
                     (swap! events conj :persist)

                     (dch/commit? event)
                     (swap! events conj (commit-target page1 (deref event)))))
                 (ptk/input-stream store))
       (await (tohs/run-store*
               store [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
               tohs/stop-on-send-update-indices))
       (t/is (= [:tokens :current-page :persist] @events))))))

(t/deftest ^:async the-other-pages-go-in-the-background
  (await
   (mock/with-mocks* {cf/flags (tohp/lazy-flags-without-drain)}
     (let [file   (tohp/setup-file-with-pages)
           store  (ths/setup-store file)
           token  (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
           page1  (cthf/current-page-id file)
           events (atom [])]
       (rx/subs! (fn [event]
                   (cond
                     (ptk/type? ::dwtpr/add-work event)
                     (swap! events conj [:add-work (deref event)])

                     (ptk/type? :app.main.data.workspace.undo/commit-undo-transaction event)
                     (swap! events conj :undo-entry)

                     (dch/commit? event)
                     (let [commit (deref event)]
                       (swap! events conj [(commit-target page1 commit)
                                           (:save-undo? commit true)]))))
                 (ptk/input-stream store))
       (await (tohs/run-store*
               store [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
               tohs/stop-on-send-update-indices))
       (t/is (= [[:tokens true] [:add-work 1] [:current-page true] :undo-entry] @events)
             "the undo entry closes with the current page, nothing waits for the other pages")))))

(defn- component-thumbnail
  "Id of the component thumbnail of `label`, on the page `page-label`."
  [file page-label label]
  (thc/fmt-object-id (:id file) (cthi/id page-label) (cthi/id label) "component"))

(t/deftest ^:async the-other-pages-wait-for-a-pause-of-the-user
  (let [pause (rx/subject)]
    (await
     (mock/with-mocks* {cf/flags      (tohp/lazy-flags-without-drain)
                        activity/idle (fn ([_ _] pause) ([_ _ _] pause))}
       (let [file   (tohp/setup-file-with-pages)
             store  (ths/setup-store file)
             token  (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
             thumb  (component-thumbnail file :page2 :main-root)
             kept?  #(contains? (:thumbnails %) thumb)
             before (atom nil)]
         (swap! store assoc :thumbnails {thumb {:uri "main"}})
         ;; The user makes a pause 50 ms after the current page is set
         (->> (ptk/input-stream store)
              (rx/filter (ptk/type? :app.main.data.workspace.undo/commit-undo-transaction))
              (rx/take 1)
              (rx/delay 50)
              (rx/subs! (fn [_]
                          (reset! before (kept? @store))
                          (rx/push! pause nil)
                          (rx/end! pause))))
         (let [state (await (tohs/run-store*
                             store [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
                             (fn [stream]
                               (rx/filter #(and (ptk/type? ::dwt/clear-thumbnail %) (= thumb (deref %)))
                                          stream))))]
           (t/is (true? @before) "nothing is cleared while the user works")
           (t/is (not (kept? state)) "the thumbnail goes after the pause")))))))

(t/deftest ^:async lazy-changes-clear-the-stale-main-thumbnails
  (await
   (mock/with-mocks* {cf/flags (tohp/lazy-flags-without-drain)}
     (let [file  (let [file          (tohp/setup-file)
                       first-page-id (cthf/current-page-id file)]
                   (-> file
                       (cthf/add-sample-page :page2)
                       (ctho/add-simple-component :component1 :main-root :main-child)
                       (ctht/apply-token-to-shape :main-root "radius" [:r1] [:r1] 50)
                       (cths/update-shape :main-root :token-values {:r1 50})
                       (ctho/add-simple-component :component2 :plain-root :plain-child)
                       (vary-meta assoc :current-page-id first-page-id)))
           store (ths/setup-store file)
           token (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
           main  (component-thumbnail file :page2 :main-root)
           plain (component-thumbnail file :page2 :plain-root)]
       (swap! store assoc :thumbnails {main {:uri "main"} plain {:uri "plain"}})
       (let [state (await (tohs/run-store*
                           store [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
                           tohs/stop-on-send-update-indices))]
         (t/is (= 50 (:r1 (cths/get-shape (ths/get-file-from-state state) :main-root :page-label :page2)))
               "the main waits to be set")
         (t/is (not (contains? (:thumbnails state) main)) "its thumbnail goes")
         (t/is (contains? (:thumbnails state) plain) "a main without the token keeps it"))))))

(t/deftest ^:async lazy-changes-clear-the-stale-frame-thumbnails
  (await
   (mock/with-mocks* {cf/flags (tohp/lazy-flags-without-drain)}
     (let [file  (let [file          (tohp/setup-file)
                       first-page-id (cthf/current-page-id file)]
                   (-> file
                       (cthf/add-sample-page :page2)
                       (ctho/add-frame :other)
                       (ctht/apply-token-to-shape :other "radius" [:r1] [:r1] 50)
                       (cths/update-shape :other :token-values {:r1 50})
                       (ctho/add-frame :plain)
                       (vary-meta assoc :current-page-id first-page-id)))
           store (ths/setup-store file)
           token (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
           thumb #(thc/fmt-object-id (:id file) (cthi/id :page2) (cthi/id %) "frame")]
       (swap! store assoc :thumbnails {(thumb :other) {:uri "other"}
                                       (thumb :plain) {:uri "plain"}})
       (let [state (await (tohs/run-store*
                           store [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
                           tohs/stop-on-send-update-indices))]
         (t/is (= 50 (:r1 (cths/get-shape (ths/get-file-from-state state) :other :page-label :page2)))
               "the frame waits to be set")
         (t/is (not (contains? (:thumbnails state) (thumb :other))) "its thumbnail goes")
         (t/is (contains? (:thumbnails state) (thumb :plain)) "a frame without the token keeps it"))))))

(t/deftest nested-mains-lose-their-thumbnail
  (let [[container main rect other] (repeatedly 4 uuid/next)
        objects {uuid/zero {:id uuid/zero :parent-id uuid/zero :type :frame}
                 container {:id container :parent-id uuid/zero :type :frame}
                 main      {:id main :parent-id container :type :frame :main-instance true}
                 rect      {:id rect :parent-id main :type :rect}
                 other     {:id other :parent-id container :type :rect}}]
    (t/is (= #{main} (pv/main-instance-ids objects [rect])) "the main that holds it, inside a container")
    (t/is (= #{main} (pv/main-instance-ids objects [main])))
    (t/is (empty? (pv/main-instance-ids objects [other])) "no main holds it")))

(defn- ^:async failed-propagation-ends-its-work
  "Checks that a token change whose propagation fails reports the failure
  and ends its work."
  []
  (let [file   (tohp/setup-file-with-pages)
        errors (atom 0)
        store  (ths/setup-store file {:on-error (fn [_] (swap! errors inc))})
        token  (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
        events (atom [])]
    (rx/subs! #(cond
                 (ptk/type? ::dwtpr/add-work %)  (swap! events conj [:add (deref %)])
                 (ptk/type? ::dwtpr/work-done %) (swap! events conj [:done (deref %)]))
              (ptk/input-stream store))
    (await (tohs/run-store*
            store [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
            (tohs/stop-on ::dwtp/propagation-finished)))
    (let [added (reduce + (keep #(when (= :add (first %)) (second %)) @events))
          ended (reduce + (keep #(when (= :done (first %)) (second %)) @events))]
      (t/is (pos? @errors) "the failure is reported")
      (t/is (pos? added))
      (t/is (= added ended) "the dialog closes"))))

(t/deftest ^:async a-failed-propagation-still-ends-its-work
  (await
   (mock/with-mocks* {cf/flags            (disj cf/flags :lazy-token-propagation)
                      pv/page-propagation (fn [& _] (throw (ex-info "failed" {})))}
     (await (failed-propagation-ends-its-work)))))

(t/deftest ^:async a-failed-search-of-the-shapes-still-ends-its-work
  (await
   (mock/with-mocks* {cf/flags                  (disj cf/flags :lazy-token-propagation)
                      cltp/out-of-date-targets (fn [& _] (throw (ex-info "failed" {})))}
     (await (failed-propagation-ends-its-work)))))
