;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.propagation-test
  "The propagation of a change of the tokens to the pages of the file
  (`app.main.data.workspace.tokens.propagation`)."
  (:require
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
   [app.main.data.workspace.tokens.correction :as dwtc]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.progress :as dwtpr]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.util.activity :as activity]
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

(t/deftest lazy-changes-set-only-the-current-page
  (t/async
    done
    (let [restore (tohp/with-lazy-flag)
          file    (tohp/setup-file-with-pages)
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          page2   #(cths/get-shape (ths/get-file-from-state %1) %2 :page-label :page2)]
      (tohs/run-store-async
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [state]
         (restore)
         (t/is (= 70 (:r1 (cths/get-shape (ths/get-file-from-state state) :stale))) "the current page")
         (t/is (= 50 (:r1 (page2 state :other))) "the other pages wait")
         (t/is (= 50 (:r1 (page2 state :main-root))) "their main components too"))))))

(t/deftest lazy-pages-are-set-when-opened
  (t/async
    done
    (let [restore (tohp/with-lazy-flag)
          file    (tohp/setup-file-with-pages)
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          page2   (cthi/id :page2)]
      (tohs/run-store
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))
                   (after-propagations #(assoc % :current-page-id page2)
                                       (dwtc/check-page {:opening? true}))]
       (fn [state]
         (restore)
         (t/is (= 70 (:r1 (cths/get-shape (ths/get-file-from-state state) :other :page-label :page2)))))
       (fn [stream]
         (->> stream
              (rx/filter #(and (ptk/type? ::dwtc/tokens-ready %)
                               (= page2 (:page-id (deref %)))))
              (rx/take 1)))))))

(defn- commit-target
  "What `commit` sets: the tokens alone, the page `page1` or another page."
  [page1 commit]
  (let [page-ids (into #{} (keep :page-id) (:redo-changes commit))]
    (cond
      (empty? page-ids) :tokens
      (contains? page-ids page1) :current-page
      :else :other-page)))

(t/deftest the-change-is-saved-and-the-other-pages-are-not
  (t/async
    done
    (let [flags   cf/flags
          _       (set! cf/flags (-> flags
                                     (conj :lazy-token-propagation)
                                     (disj :lazy-token-propagation-drain)))
          file    (tohp/setup-file-with-pages)
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          page1   (cthf/current-page-id file)
          events  (atom [])]
      (rx/subs! (fn [event]
                  (cond
                    (= :app.main.data.persistence/force-persist event)
                    (swap! events conj :persist)

                    (dch/commit? event)
                    (swap! events conj (commit-target page1 (deref event)))))
                (ptk/input-stream store))
      (tohs/run-store-async
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [_]
         (set! cf/flags flags)
         (t/is (= [:tokens :current-page :persist] @events)))))))

(t/deftest the-other-pages-go-in-the-background
  (t/async
    done
    (let [flags   cf/flags
          _       (set! cf/flags (-> flags
                                     (conj :lazy-token-propagation)
                                     (disj :lazy-token-propagation-drain)))
          file    (tohp/setup-file-with-pages)
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          page1   (cthf/current-page-id file)
          events  (atom [])]
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
      (tohs/run-store-async
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [_]
         (set! cf/flags flags)
         (t/is (= [[:tokens true] [:add-work 1] [:current-page true] :undo-entry] @events)
               "the undo entry closes with the current page, nothing waits for the other pages"))))))

(defn- without-drain
  "Run with the lazy token propagation flag on and its drain off; it
  returns the restore function to call when the test ends."
  []
  (let [flags cf/flags]
    (set! cf/flags (-> flags
                       (conj :lazy-token-propagation)
                       (disj :lazy-token-propagation-drain)))
    #(set! cf/flags flags)))

(defn- component-thumbnail
  "Id of the component thumbnail of `label`, on the page `page-label`."
  [file page-label label]
  (thc/fmt-object-id (:id file) (cthi/id page-label) (cthi/id label) "component"))

(t/deftest the-other-pages-wait-for-a-pause-of-the-user
  (t/async
    done
    (let [restore (without-drain)
          idle    activity/idle
          pause   (rx/subject)
          file    (tohp/setup-file-with-pages)
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          thumb   (component-thumbnail file :page2 :main-root)
          kept?   #(contains? (:thumbnails %) thumb)
          before  (atom nil)]
      (swap! store assoc :thumbnails {thumb {:uri "main"}})
      (set! activity/idle (fn ([_ _] pause) ([_ _ _] pause)))
      (tohs/run-store
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [state]
         (restore)
         (set! activity/idle idle)
         (t/is (true? @before) "nothing is cleared while the user works")
         (t/is (not (kept? state)) "the thumbnail goes after the pause"))
       (fn [stream]
         ;; The user makes a pause 50 ms after the current page is set
         (->> stream
              (rx/filter (ptk/type? :app.main.data.workspace.undo/commit-undo-transaction))
              (rx/take 1)
              (rx/delay 50)
              (rx/subs! (fn [_]
                          (reset! before (kept? @store))
                          (rx/push! pause nil)
                          (rx/end! pause))))
         (->> stream
              (rx/filter #(and (ptk/type? ::dwt/clear-thumbnail %) (= thumb (deref %))))
              (rx/take 1)
              (rx/delay 10)))))))

(t/deftest lazy-changes-clear-the-stale-main-thumbnails
  (t/async
    done
    (let [restore (without-drain)
          file    (let [file          (tohp/setup-file)
                        first-page-id (cthf/current-page-id file)]
                    (-> file
                        (cthf/add-sample-page :page2)
                        (ctho/add-simple-component :component1 :main-root :main-child)
                        (ctht/apply-token-to-shape :main-root "radius" [:r1] [:r1] 50)
                        (cths/update-shape :main-root :token-values {:r1 50})
                        (ctho/add-simple-component :component2 :plain-root :plain-child)
                        (vary-meta assoc :current-page-id first-page-id)))
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          main    (component-thumbnail file :page2 :main-root)
          plain   (component-thumbnail file :page2 :plain-root)]
      (swap! store assoc :thumbnails {main {:uri "main"} plain {:uri "plain"}})
      (tohs/run-store-async
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [state]
         (restore)
         (t/is (= 50 (:r1 (cths/get-shape (ths/get-file-from-state state) :main-root :page-label :page2)))
               "the main waits to be set")
         (t/is (not (contains? (:thumbnails state) main)) "its thumbnail goes")
         (t/is (contains? (:thumbnails state) plain) "a main without the token keeps it"))))))

(t/deftest lazy-changes-clear-the-stale-frame-thumbnails
  (t/async
    done
    (let [restore (without-drain)
          file    (let [file          (tohp/setup-file)
                        first-page-id (cthf/current-page-id file)]
                    (-> file
                        (cthf/add-sample-page :page2)
                        (ctho/add-frame :other)
                        (ctht/apply-token-to-shape :other "radius" [:r1] [:r1] 50)
                        (cths/update-shape :other :token-values {:r1 50})
                        (ctho/add-frame :plain)
                        (vary-meta assoc :current-page-id first-page-id)))
          store   (ths/setup-store file)
          token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
          thumb   #(thc/fmt-object-id (:id file) (cthi/id :page2) (cthi/id %) "frame")]
      (swap! store assoc :thumbnails {(thumb :other) {:uri "other"}
                                      (thumb :plain) {:uri "plain"}})
      (tohs/run-store-async
       store done [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
       (fn [state]
         (restore)
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
    (t/is (= #{main} (dwtp/main-instance-ids objects [rect])) "the main that holds it, inside a container")
    (t/is (= #{main} (dwtp/main-instance-ids objects [main])))
    (t/is (empty? (dwtp/main-instance-ids objects [other])) "no main holds it")))
