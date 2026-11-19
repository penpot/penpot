;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns frontend-tests.tokens.drain-test
  "The drain sets in a worker of its own the pages that a lazy change of the
  tokens left out of date. The tests run the worker's work inline."
  (:require
   [app.common.data :as d]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.files :as cthf]
   [app.common.test-helpers.ids-map :as cthi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as ctht]
   [app.common.types.objects-map :as omap]
   [app.common.types.tokens-lib :as ctob]
   [app.config :as cf]
   [app.main.data.changes :as dch]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace.thumbnails :as dwt]
   [app.main.data.workspace.tokens.drain :as drain]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.page-values :as pv]
   [app.main.fonts :as fonts]
   [app.util.activity :as activity]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [cuerdas.core :as str]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [frontend-tests.tokens.helpers.state :as tohs]
   [frontend-tests.tokens.helpers.tokens :as toht]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(defn- setup-file
  "A file with the token `radius` (50) on the current page and a second page
  whose frame `other` holds 10, a value of an older version of the token."
  []
  (let [file          (-> (toht/radius-file)
                          (ctho/add-frame :current))
        first-page-id (cthf/current-page-id file)]
    (-> file
        (cthf/add-sample-page :page2)
        (ctho/add-frame :other)
        (ctht/apply-token-to-shape :other "radius" [:r1] [:r1] 10)
        (cths/update-shape :other :token-values {:r1 10})
        (vary-meta assoc :current-page-id first-page-id))))

(defn- page2-id
  [file]
  (:id (cthf/get-page file :page2)))

(defn- inline-worker
  "Stand-in for the drain worker: the work of `app.worker.tokens-drain`, run
  here, and the requests it got in `requests`."
  [requests]
  (fn [{:keys [page values token-names] :as message}]
    (swap! requests conj (select-keys message [:values :token-names]))
    (rx/of (pv/drain-page page values token-names @fonts/fontsdb))))

(defn- inline-thumbnails
  "Stand-in for the drain worker when it looks for the stale thumbnails."
  [{:keys [page values token-names]}]
  (rx/of (pv/page-stale-thumbnails page values token-names)))

(def ^:private thumbnail-deletion-ms
  "Longer than the wait before the cleared thumbnails are deleted in a batch."
  250)

(defn- with-drain
  "Runs `body`, an async function, with the lazy propagation, its drain, no
  waits and `request` as the worker that sets the pages; returns a promise
  that restores them once `body` settles. Options: `:thumbnails`, the worker
  that looks for their thumbnails, and `:chunk-size` of the drain.

  When the drain cleared thumbnails, it also waits for their deletion, so
  its request does not reach the mocks of the tests that follow."
  ([request body]
   (with-drain request nil body))
  ([request {:keys [thumbnails chunk-size] :or {thumbnails inline-thumbnails}} body]
   (let [cleared? (volatile! false)
         clear    dwt/clear-stale-thumbnails]
     (mock/with-mocks*
       {cf/flags                   (conj cf/flags :lazy-token-propagation :lazy-token-propagation-drain)
        activity/pause-ms          1
        drain/ask-worker           #(if (= :tokens/stale-thumbnails (:cmd %))
                                      (thumbnails %)
                                      (request %))
        drain/chunk-size           (or chunk-size drain/chunk-size)
        dwt/clear-stale-thumbnails (fn [& args]
                                     (vreset! cleared? true)
                                     (apply clear args))}
       (await (body))
       (when @cleared?
         (await (js/Promise. #(js/setTimeout % thumbnail-deletion-ms))))))))

(defn- r1
  [state label]
  (:r1 (cths/get-shape (ths/get-file-from-state state) label :page-label :page2)))

(defn- drained?
  "Stopper of `run-store*` once the run of the drain ends."
  [stream]
  (rx/filter (ptk/type? ::drain/run-ended) stream))

(defn- stopped?
  "Stopper of `run-store*` once the drain stops."
  [stream]
  (rx/filter (ptk/type? ::drain/stop) stream))

;; --- The work of the worker

(t/deftest the-worker-sets-the-out-of-date-shapes-of-a-page
  (let [file   (setup-file)
        page   (cthf/get-page file :page2)
        result (pv/drain-page page {"radius" 70} ["radius"] {})]
    (t/is (= 70 (-> (cthf/apply-changes file {:redo-changes (:redo-changes result)})
                    (cths/get-shape :other :page-label :page2)
                    :r1)))
    (t/is (= #{(cthi/id :other)} (:frame-ids result)) "the root frame whose thumbnail goes")
    (t/is (nil? (pv/drain-page page {"radius" 10} ["radius"] {})) "nothing out of date")))

(t/deftest the-worker-finds-the-stale-thumbnails-of-a-page
  (let [page (cthf/get-page (setup-file) :page2)]
    (t/is (= {:frame-ids #{(cthi/id :other)} :main-ids #{}}
             (pv/page-stale-thumbnails page {"radius" 70} ["radius"])))
    (t/is (nil? (pv/page-stale-thumbnails page {"radius" 10} ["radius"])) "nothing out of date")))

(t/deftest a-page-is-applied-only-when-it-still-holds-what-was-read
  (let [file   (setup-file)
        page   (cthf/get-page file :page2)
        result (pv/drain-page page {"radius" 70} ["radius"] {})
        edited (cths/update-shape file :other :r1 20 :page-label :page2)]
    (t/is (drain/holds-read-values? page (:undo-changes result)))
    (t/is (not (drain/holds-read-values? (cthf/get-page edited :page2) (:undo-changes result)))
          "the user changed the shape meanwhile")))

(t/deftest a-big-page-is-applied-in-chunks-with-their-undo
  (let [ids    (range 1201)
        mod    (fn [id] {:type :mod-obj :id id :operations []})
        result {:redo-changes (conj (mapv mod ids) {:type :mod-page :id :page})
                :undo-changes (conj (mapv mod ids) {:type :mod-page :id :page})}
        parts  (vec (#'drain/chunks result))]
    (t/is (= [500 500 202] (mapv (comp count :redo-changes) parts)))
    (t/is (every? #(= (map :id (filter (comp #{:mod-obj} :type) (:redo-changes %)))
                      (map :id (filter (comp #{:mod-obj} :type) (:undo-changes %))))
                  parts))
    (t/is (= :mod-page (:type (peek (:redo-changes (peek parts))))) "the other changes go last")))

(t/deftest the-changes-of-a-shape-go-in-the-same-chunk
  (let [mod    (fn [id attr] {:type :mod-obj :id id :operations [{:type :set :attr attr}]})
        result {:redo-changes [(mod 1 :a) (mod 2 :a) (mod 1 :b) (mod 2 :b) (mod 3 :a)]
                :undo-changes [(mod 1 :a) (mod 2 :a) (mod 1 :b) (mod 2 :b) (mod 3 :a)]}
        size   drain/chunk-size
        parts  (try
                 (set! drain/chunk-size 2)
                 (vec (#'drain/chunks result))
                 (finally (set! drain/chunk-size size)))
        ids    (fn [k] (mapv #(mapv :id (get % k)) parts))]
    (t/is (= [[1 1 2 2] [3]] (ids :redo-changes)))
    (t/is (= (ids :redo-changes) (ids :undo-changes)))))

(defn- setup-size-file
  "A file with the token `size` (70) and a second page whose frames `one`
  and `two` apply it to their width, set to 10 by an older version."
  []
  (let [file          (-> (toht/sample-file-with-tokens [{:label :size :name "size" :type :sizing :value 70}])
                          (ctho/add-frame :current))
        first-page-id (cthf/current-page-id file)
        add-frame     (fn [file label]
                        (-> file
                            (ctho/add-frame label {:width 10 :height 10})
                            (ctht/apply-token-to-shape label "size" [:width] [:width] 10)
                            (cths/update-shape label :token-values {:width 10})))]
    (-> file
        (cthf/add-sample-page :page2)
        (add-frame :one)
        (add-frame :two)
        (vary-meta assoc :current-page-id first-page-id))))

(t/deftest ^:async a-shape-is-set-whole-when-its-changes-are-many
  ;; The values of the shapes come first in the changes and their geometry
  ;; after, so a shape's changes would otherwise go in different chunks
  (await
   (with-drain
     (inline-worker (atom [])) {:chunk-size 1}
     (^:async fn []
       (let [file    (setup-size-file)
             store   (ths/setup-store file)
             page-id (page2-id file)
             state   (await (tohs/run-store*
                             store [(drain/start {"size" {:resolved-value 70}} {"size" {}} [page-id])]
                             drained?))
             file'   (ths/get-file-from-state state)
             shape   #(cths/get-shape file' % :page-label :page2)]
         (doseq [label [:one :two]]
           (t/is (= 70 (:width (shape label))) (str (name label) " is resized"))
           (t/is (= {:width 70} (:token-values (shape label))) (str (name label) " records the value"))))))))

;; --- The drain

(t/deftest ^:async a-lazy-change-drains-the-other-pages-in-the-worker
  (let [requests (atom [])]
    (await
     (with-drain
       (inline-worker requests)
       (^:async fn []
         (let [file    (setup-file)
               store   (ths/setup-store file)
               token   (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
               commits (tohs/watch-commits store)
               state   (await (tohs/run-store*
                               store [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
                               drained?))]
           (t/is (= 70 (r1 state :other)) "the other page is set")
           (t/is (= [{:values {"radius" 70} :token-names ["radius"]}] @requests) "once, for the change")
           (t/is (false? (:save-undo? (last @commits))) "not undoable")
           (t/is (nil? (::drain/drain state)) "the run ends")))))))

(t/deftest ^:async the-shapes-changed-in-memory-are-encoded-before-the-request
  (let [changed (atom [])
        seen    (fn [{:keys [page]}]
                  (swap! changed conj (omap/changed-ids (:objects page))))]
    (await
     (with-drain
       (fn [message]
         (seen message)
         ((inline-worker (atom [])) message))
       {:thumbnails (fn [message]
                      (seen message)
                      (inline-thumbnails message))}
       (^:async fn []
         (let [file     (setup-file)
               other-id (cthi/id :other)
               ;; the shapes of the other page as loaded, one of them changed since
               file     (update-in file [:data :pages-index (page2-id file) :objects]
                                   #(-> (omap/wrap %)
                                        (assoc other-id (get % other-id))))
               store    (ths/setup-store file)
               token    (ctob/get-token (ctht/get-tokens-lib file) (cthi/id :set) (cthi/id :radius))
               state    (await (tohs/run-store*
                                store [(dwtl/update-token (cthi/id :set) (cthi/id :radius) (assoc token :value 70))]
                                drained?))]
           (t/is (= 70 (r1 state :other)) "the other page is set")
           (t/is (= [[] []] @changed) "both requests find every shape encoded")))))))

(t/deftest ^:async the-same-pages-are-not-drained-twice
  (let [requests (atom [])
        answer   (rx/subject)]
    (await
     (with-drain
       (fn [message]
         (swap! requests conj (:page message))
         answer)
       (^:async fn []
         (let [file     (setup-file)
               store    (ths/setup-store file)
               page-id  (page2-id file)
               resolved {"radius" {:name "radius" :resolved-value 70}}
               state    (await (tohs/run-store*
                                store [(drain/start resolved {"radius" {}} [page-id])
                                       ;; The same tokens again while the page is in the worker
                                       (drain/start resolved {"radius" {}} [page-id])
                                       (ptk/reify ::answer
                                         ptk/EffectEvent
                                         (effect [_ state _]
                                           (rx/push! answer (pv/drain-page (dsh/lookup-page state page-id)
                                                                           {"radius" 70} ["radius"] {}))
                                           (rx/end! answer)))]
                                drained?))]
           (t/is (= 1 (count @requests)))
           (t/is (= 70 (r1 state :other)))))))))

(t/deftest ^:async newer-tokens-replace-the-pages-waiting
  (let [requests (atom [])
        old      (rx/subject)]
    (await
     (with-drain
       (fn [{:keys [page values token-names]}]
         (swap! requests conj values)
         (if (= {"radius" 70} values)
           ;; The older tokens wait in the worker
           old
           (rx/of (pv/drain-page page values token-names {}))))
       (^:async fn []
         (let [file    (setup-file)
               store   (ths/setup-store file)
               page-id (page2-id file)
               state   (await (tohs/run-store*
                               store [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} [page-id])
                                      (drain/start {"radius" {:resolved-value 80}} {"radius" {}} [page-id])
                                      (ptk/reify ::answer-old
                                        ptk/EffectEvent
                                        (effect [_ state _]
                                          (rx/push! old (pv/drain-page (dsh/lookup-page state page-id)
                                                                       {"radius" 70} ["radius"] {}))
                                          (rx/end! old)))]
                               drained?))]
           (t/is (= [{"radius" 70} {"radius" 80}] @requests) "the page is asked again for the new tokens")
           (t/is (= 80 (r1 state :other)) "the result for the older tokens is discarded")))))))

(t/deftest ^:async a-failing-worker-stops-the-drain
  (await
   (with-drain
     (fn [_] (rx/throw (ex-info "worker gone" {})))
     (^:async fn []
       (let [file    (setup-file)
             store   (ths/setup-store file)
             commits (tohs/watch-commits store)]
         (await (tohs/run-store*
                 store [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} [(page2-id file)])]
                 stopped?))
         (await (async/settle))
         (let [state @store]
           (t/is (nil? (::drain/drain state)))
           (t/is (empty? @commits) "the page is left for the editors")
           (t/is (= 10 (r1 state :other)))))))))

(t/deftest ^:async the-drain-stops-when-the-tokens-change
  (let [answer (rx/subject)]
    (await
     (with-drain
       (fn [_] answer)
       (^:async fn []
         (let [file    (setup-file)
               store   (ths/setup-store file)
               page-id (page2-id file)]
           (await (tohs/run-store*
                   store [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} [page-id])
                          ;; An undo of the change, while the page is in the worker
                          (dch/commit-changes {:redo-changes [{:type :set-tokens-status
                                                               :theme-ids #{(cthi/id :theme)}
                                                               :set-ids #{(cthi/id :set)}}]
                                               :undo-changes []
                                               :save-undo? false})
                          (ptk/reify ::answer
                            ptk/EffectEvent
                            (effect [_ state _]
                              (rx/push! answer (pv/drain-page (dsh/lookup-page state page-id)
                                                              {"radius" 70} ["radius"] {}))
                              (rx/end! answer)))]
                   stopped?))
           ;; The answer comes after the stop
           (await (async/settle))
           (let [state @store]
             (t/is (nil? (::drain/drain state)))
             (t/is (= 10 (r1 state :other)) "the page is left for the editors"))))))))

;; --- The thumbnails

(defn- three-pages-file
  "`setup-file` with a third page whose frame `third` holds 10 too."
  []
  (let [base (setup-file)]
    (-> base
        (cthf/add-sample-page :page3)
        (ctho/add-frame :third)
        (ctht/apply-token-to-shape :third "radius" [:r1] [:r1] 10)
        (cths/update-shape :third :token-values {:r1 10})
        (vary-meta assoc :current-page-id (cthf/current-page-id base)))))

(t/deftest ^:async the-thumbnails-of-every-page-are-cleared-before-any-page-is-set
  (await
   (with-drain
     (inline-worker (atom []))
     (^:async fn []
       (let [events (atom [])
             file   (three-pages-file)
             store  (ths/setup-store file)
             pages  [(page2-id file) (:id (cthf/get-page file :page3))]
             frame? (fn [label] #(and (ptk/type? ::dwt/clear-thumbnail %)
                                      (str/includes? (deref %) (str (cthi/id label)))))]
         (->> (ptk/input-stream store)
              (rx/filter #(or (dch/commit? %) (ptk/type? ::dwt/clear-thumbnail %)))
              (rx/subs! #(swap! events conj %)))
         (await (tohs/run-store*
                 store [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} pages)]
                 drained?))
         (let [first-commit (d/index-of-pred @events dch/commit?)]
           (t/is (some? first-commit))
           (t/is (some (frame? :other) (take first-commit @events)))
           (t/is (some (frame? :third) (take first-commit @events)))))))))

(t/deftest ^:async the-thumbnails-are-cleared-when-the-pages-are-never-set
  (await
   (with-drain
     (fn [_] (rx/throw (ex-info "worker gone" {})))
     (^:async fn []
       (let [cleared (atom [])
             file    (setup-file)
             store   (ths/setup-store file)]
         (->> (ptk/input-stream store)
              (rx/filter (ptk/type? ::dwt/clear-thumbnail))
              (rx/subs! #(swap! cleared conj (deref %))))
         (await (tohs/run-store*
                 store [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} [(page2-id file)])]
                 stopped?))
         (await (async/settle))
         (t/is (= 10 (r1 @store :other)) "the page is not set")
         (t/is (some #(str/includes? % (str (cthi/id :other))) @cleared) "its thumbnail is cleared"))))))

;; --- A change while the drain runs

(defn- third
  [state]
  (:r1 (cths/get-shape (ths/get-file-from-state state) :third :page-label :page3)))

(defn- held-worker
  "Stand-in for the drain worker that holds the requests for `held` values
  in `answer` and runs the others inline; the requests it got go to
  `requests`."
  [requests held answer]
  (fn [{:keys [page values token-names] :as message}]
    (if (= held values)
      answer
      (do (swap! requests conj (select-keys message [:values :token-names]))
          (rx/of (pv/drain-page page values token-names {}))))))

(t/deftest ^:async a-change-of-other-tokens-keeps-the-pages-the-stopped-run-left
  (let [requests (atom [])]
    (await
     (with-drain
       (held-worker requests {"radius" 70} (rx/subject))
       (^:async fn []
         (let [file  (three-pages-file)
               store (ths/setup-store file)
               pages [(page2-id file) (:id (cthf/get-page file :page3))]
               state (await (tohs/run-store*
                             store [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} pages)
                                    ;; A change of another token stops the run
                                    (dch/commit-changes {:redo-changes [{:type :set-tokens-status
                                                                         :theme-ids #{(cthi/id :theme)}
                                                                         :set-ids #{(cthi/id :set)}}]
                                                         :undo-changes []
                                                         :save-undo? false})
                                    (drain/start {"radius" {:resolved-value 70} "size" {:resolved-value 5}}
                                                 {"size" {}} pages)]
                             drained?))]
           (t/is (every? #(= ["size" "radius"] (:token-names %)) @requests)
                 "the next run looks for the tokens of both changes")
           (t/is (= 70 (r1 state :other)))
           (t/is (= 70 (third state)))
           (t/is (nil? (::drain/left state)))))))))

(t/deftest ^:async newer-tokens-keep-the-token-names-of-the-run-they-follow
  (let [requests (atom [])
        old      (rx/subject)]
    (await
     (with-drain
       (held-worker requests {"radius" 70} old)
       (^:async fn []
         (let [file  (three-pages-file)
               store (ths/setup-store file)
               pages [(page2-id file) (:id (cthf/get-page file :page3))]
               state (await (tohs/run-store*
                             store [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} pages)
                                    (drain/start {"radius" {:resolved-value 70} "size" {:resolved-value 5}}
                                                 {"size" {}} pages)
                                    (ptk/reify ::answer-first-run
                                      ptk/EffectEvent
                                      (effect [_ state _]
                                        (rx/push! old (pv/drain-page (dsh/lookup-page state (first pages))
                                                                     {"radius" 70} ["radius"] {}))
                                        (rx/end! old)))]
                             drained?))]
           (t/is (seq @requests))
           (t/is (every? #(= ["size" "radius"] (:token-names %)) @requests))
           (t/is (= 70 (r1 state :other)))
           (t/is (= 70 (third state)))))))))

;; --- The pages that an editor has open

(defn- open-page
  "Event that opens `page-id` in this session."
  [page-id]
  (ptk/reify ::open-page
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :current-page-id page-id))))

(defn- presence
  "Event that shows another session on `page-id`."
  [page-id can-edit]
  (ptk/reify ::presence
    ptk/UpdateEvent
    (update [_ state]
      (assoc state :workspace-presence {(random-uuid) {:page-id page-id :can-edit can-edit}}))))

(defn- drain-with
  "Runs the drain of the second page after `before`, and checks the state
  and the pages the worker got with `check`."
  [before check]
  (let [requests (atom [])]
    (with-drain
      (inline-worker requests)
      (^:async fn []
        (let [file    (setup-file)
              store   (ths/setup-store file)
              page-id (page2-id file)
              state   (await (tohs/run-store*
                              store (conj (vec (before page-id))
                                          (drain/start {"radius" {:resolved-value 70}} {"radius" {}} [page-id]))
                              drained?))]
          (check state @requests))))))

(t/deftest ^:async the-drain-leaves-the-page-this-session-has-open
  (await (drain-with (fn [page-id] [(open-page page-id)])
                     (fn [state requests]
                       (t/is (empty? requests))
                       (t/is (= 10 (r1 state :other)) "left for the page check")))))

(t/deftest ^:async the-drain-leaves-the-page-another-editor-has-open
  (await (drain-with (fn [page-id] [(presence page-id true)])
                     (fn [state requests]
                       (t/is (empty? requests))
                       (t/is (= 10 (r1 state :other)) "left for the editor on it")))))

(t/deftest ^:async the-drain-sets-the-page-only-viewers-have-open
  (await (drain-with (fn [page-id] [(presence page-id false)])
                     (fn [state requests]
                       (t/is (= 1 (count requests)))
                       (t/is (= 70 (r1 state :other)) "viewers don't save it")))))

(t/deftest ^:async the-drain-discards-a-page-opened-while-in-the-worker
  (let [answer (rx/subject)
        sent   (atom 0)]
    (await
     (with-drain
       (fn [_] (swap! sent inc) answer)
       (^:async fn []
         (let [file    (setup-file)
               store   (ths/setup-store file)
               page-id (page2-id file)
               state   (await (tohs/run-store*
                               store [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} [page-id])
                                      (presence page-id true)
                                      (ptk/reify ::answer
                                        ptk/EffectEvent
                                        (effect [_ state _]
                                          (rx/push! answer (pv/drain-page (dsh/lookup-page state page-id)
                                                                          {"radius" 70} ["radius"] {}))
                                          (rx/end! answer)))]
                               drained?))]
           (t/is (= 1 @sent) "sent before the editor opened it")
           (t/is (= 10 (r1 state :other)) "left for the editor on it")))))))

(t/deftest ^:async an-edit-between-chunks-stops-the-page
  (await
   (with-drain
     (inline-worker (atom [])) {:chunk-size 1}
     (^:async fn []
       (let [base    (setup-file)
             file    (-> base
                         (vary-meta assoc :current-page-id (page2-id base))
                         (ctho/add-frame :another)
                         (ctht/apply-token-to-shape :another "radius" [:r1] [:r1] 10)
                         (cths/update-shape :another :token-values {:r1 10})
                         (vary-meta assoc :current-page-id (cthf/current-page-id base)))
             store   (ths/setup-store file)
             page-id (page2-id file)
             edited  (atom nil)]
         ;; Another session edits the shape of the second chunk after the first
         (->> (ptk/input-stream store)
              (rx/filter dch/commit?)
              (rx/map deref)
              (rx/filter #(contains? (:tags %) :token-propagation))
              (rx/take 1)
              (rx/subs! (fn [{:keys [redo-changes]}]
                          (let [first-id (:id (first redo-changes))
                                other-id (if (= first-id (cthi/id :other)) (cthi/id :another) (cthi/id :other))]
                            (reset! edited other-id)
                            (ptk/emit! store (dch/commit-changes
                                              {:redo-changes [{:type :mod-obj :page-id page-id :id other-id
                                                               :operations [{:type :set :attr :r1 :val 33}]}]
                                               :undo-changes []
                                               :save-undo? false}))))))
         (let [state (await (tohs/run-store*
                             store [(drain/start {"radius" {:resolved-value 70}} {"radius" {}} [page-id])]
                             drained?))
               file' (ths/get-file-from-state state)
               r1    #(:r1 (cths/get-shape-by-id file' % :page-label :page2))]
           (t/is (some? @edited))
           (t/is (= 33 (r1 @edited)) "the edit is kept")
           (t/is (= #{70 33} (into #{} (map r1) [(cthi/id :other) (cthi/id :another)])) "the first chunk is set")))))))
