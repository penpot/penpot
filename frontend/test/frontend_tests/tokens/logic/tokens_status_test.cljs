(ns frontend-tests.tokens.logic.tokens-status-test
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as tho]
   [app.common.types.text :as txt]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.render-wasm.api :as wasm.api]
   [app.render-wasm.api.fonts :as wasm.fonts]
   [beicon.v2.core :as rx]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.helpers.wasm :as thw]
   [frontend-tests.tokens.helpers.state :as tohs]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before (fn []
             (thp/reset-idmap!)
             (thw/setup-wasm-mocks!))
   :after  thw/teardown-wasm-mocks!})

(defn- setup-file
  "Create a file with several token themes and token sets, some active and
  some inactive. Returns the file map with :tokens-lib and :tokens-status
  in its :data."
  []
  (-> (tho/sample-file-with-tokens
       :lib-fn #(-> %
                    ;; Add token sets
                    (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-active)
                                                       :name "set-active"))
                    (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-inactive)
                                                       :name "set-inactive"))
                    ;; Add themes
                    (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-active)
                                                           :name "theme-active"
                                                           :group "group-1"
                                                           :sets #{"set-active"}))
                    (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :theme-inactive)
                                                           :name "theme-inactive"
                                                           :group "group-1"
                                                           :sets #{"set-inactive"})))
       :status-fn #(-> %
                       (ctos/set-tokens-status #{(thi/id :theme-active)}
                                               #{(thi/id :set-active)})))))

(defn- get-tokens-status [file]
  (get-in file [:data :tokens-status]))

(t/deftest test-set-token-theme-active-deactivate
  (t/async
    done
    (let [file      (setup-file)
          store     (ths/setup-store file)
          theme-id  (thi/id :theme-active)
          set-id    (thi/id :set-active)
          events    [(dwtl/set-token-theme-active theme-id false)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file'   (ths/get-file-from-state new-state)
               status  (get-tokens-status file')]
           (t/is (not (ctos/theme-active? status theme-id))
                 "theme should be inactive after deactivation")
           (t/is (not (ctos/set-active? status set-id))
                 "set should be inactive when its theme is deactivated")))))))

(t/deftest test-set-token-theme-active-activate
  (t/async
    done
    (let [file      (setup-file)
          store     (ths/setup-store file)
          theme-id  (thi/id :theme-inactive)
          set-id    (thi/id :set-inactive)
          events    [(dwtl/set-token-theme-active theme-id true)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file'   (ths/get-file-from-state new-state)
               status  (get-tokens-status file')]
           (t/is (ctos/theme-active? status theme-id)
                 "theme should be active after activation")
           (t/is (ctos/set-active? status set-id)
                 "set should be active when its theme is activated")))))))

(t/deftest test-toggle-token-theme-active-deactivate
  (t/async
    done
    (let [file      (setup-file)
          store     (ths/setup-store file)
          theme-id  (thi/id :theme-active)
          set-id    (thi/id :set-active)
          events    [(dwtl/toggle-token-theme-active theme-id)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file'   (ths/get-file-from-state new-state)
               status  (get-tokens-status file')]
           (t/is (not (ctos/theme-active? status theme-id))
                 "active theme should become inactive after toggle")
           (t/is (not (ctos/set-active? status set-id))
                 "set should be inactive when its theme is toggled off")))))))

(t/deftest test-toggle-token-theme-active-activate
  (t/async
    done
    (let [file      (setup-file)
          store     (ths/setup-store file)
          theme-id  (thi/id :theme-inactive)
          set-id    (thi/id :set-inactive)
          events    [(dwtl/toggle-token-theme-active theme-id)]]

      (tohs/run-store-async
       store done events
       (fn [new-state]
         (let [file'   (ths/get-file-from-state new-state)
               status  (get-tokens-status file')]
           (t/is (ctos/theme-active? status theme-id)
                 "inactive theme should become active after toggle")
           (t/is (ctos/set-active? status set-id)
                 "set should be active when its theme is toggled on")))))))

(defn- setup-file-with-radius
  "The sets `mobile` and `desktop` define `radius` as 8 and 16, activated
  by the themes Mobile and Desktop of the group Platform. Mobile is
  active. A rect has `radius` applied."
  []
  (-> (tho/sample-file-with-tokens
       :lib-fn #(-> %
                    (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-mobile) :name "mobile"))
                    (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-desktop) :name "desktop"))
                    (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :mobile)
                                                           :name "Mobile"
                                                           :group "Platform"
                                                           :sets #{"mobile"}))
                    (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :desktop)
                                                           :name "Desktop"
                                                           :group "Platform"
                                                           :sets #{"desktop"}))
                    (ctob/add-token (thi/id :set-mobile)
                                    (ctob/make-token {:name "radius" :value "8" :type :border-radius}))
                    (ctob/add-token (thi/id :set-desktop)
                                    (ctob/make-token {:name "radius" :value "16" :type :border-radius})))
       :status-fn #(ctos/set-tokens-status % #{(thi/id :mobile)} #{(thi/id :set-mobile)}))
      (ctho/add-rect :rect-1 :applied-tokens {:r1 "radius"})))

(t/deftest ^:async test-undo-theme-change-at-once
  (t/testing "one undo brings back the active theme and the values it propagated"
    (let [file   (setup-file-with-radius)
          store  (ths/setup-store file)
          state  #(deref store)
          radius #(:r1 (cths/get-shape (ths/get-file-from-state (state)) :rect-1))
          themes #(ctos/get-active-theme-ids (get-tokens-status (ths/get-file-from-state (state))))]
      (await
       (mock/with-mocks* {st/state store st/stream (ptk/input-stream store)}
         (st/emit! (tohs/watch-undo-stack)
                   (dwtp/propagate-workspace-tokens))
         (await (async/wait-for #(= 8 (radius)) "file propagated"))
         (st/emit! dwu/reinitialize-undo
                   (dwtl/set-token-theme-active (thi/id :desktop) true))
         (await (async/wait-for #(and (= 16 (radius))
                                      (seq (get-in (state) [:workspace-undo :items])))
                                "theme changed"))
         (t/is (contains? (themes) (thi/id :desktop)))
         (st/emit! dwu/undo)
         (await (async/wait-for #(= 8 (radius)) "undone"))
         (t/is (contains? (themes) (thi/id :mobile)))
         (t/is (not (contains? (themes) (thi/id :desktop)))))))))

;; ---- One undo action for a change and what it propagates
;;
;; The file of `setup-file-with-radius`: Mobile active, `radius` 8 on
;; `rect-1`. `rect-2` and `rect-3` take the edits made around the change.

(defn- setup-file-for-undo
  []
  (-> (setup-file-with-radius)
      (ctho/add-rect :rect-2)
      (ctho/add-rect :rect-3)))

(defn- state-file
  [store]
  (ths/get-file-from-state @store))

(defn- shape-of
  [store label]
  (cths/get-shape (state-file store) label))

(defn- radius-of
  [store]
  (:r1 (shape-of store :rect-1)))

(defn- active-themes
  [store]
  (ctos/get-active-theme-ids (get-tokens-status (state-file store))))

(defn- tokens-status-of
  "The active themes and sets."
  [store]
  (let [status (get-tokens-status (state-file store))]
    [(ctos/get-active-theme-ids status) (ctos/get-active-set-ids status)]))

(defn- rename
  "An edit independent from the tokens: names `label` `name`."
  [label name]
  (dwsh/update-shapes [(thi/id label)] #(assoc % :name name)))

(defn- pending-actions
  [store]
  (get-in @store [:workspace-undo :actions]))

(defn- ^:async start-store
  "Propagates the tokens of the file once, and starts recording undo
  entries from there."
  [store]
  (st/emit! (tohs/watch-undo-stack)
            (dwtp/propagate-workspace-tokens))
  (await (async/wait-for-store store #(= 8 (radius-of store)) "file propagated"))
  (st/emit! dwu/reinitialize-undo))

(defn- ^:async wait-action-done
  [store msg]
  (await (async/wait-for-store store #(empty? (pending-actions store)) msg)))

(def ^:private desktop-changes
  "Events that make `radius` 16: activating the Desktop theme, or its set
  (later in the list than the Mobile one, so it wins)."
  {"switching the theme" #(dwtl/set-token-theme-active (thi/id :desktop) true)
   "toggling the set"    #(dwtl/toggle-token-set (thi/id :set-desktop))})

(t/deftest ^:async test-undo-action-keeps-other-edits-apart
  (doseq [[what change] desktop-changes]
    (t/testing (str what ": an edit made while the tokens resolve is its own entry")
      (let [store (ths/setup-store (setup-file-for-undo))]
        (await
         (mock/with-mocks* {st/state store st/stream (ptk/input-stream store)}
           (await (start-store store))
           (st/emit! (rename :rect-2 "before"))
           (let [status-before (tokens-status-of store)]
             ;; The edit lands once the change is committed, before the
             ;; asynchronous resolution of the tokens
             (st/emit! (change) (rename :rect-3 "during"))
             (await (async/wait-for-store store #(= 16 (radius-of store)) "propagated"))
             (await (wait-action-done store "action done"))
             (t/is (= 3 (count (get-in @store [:workspace-undo :items]))))
             (let [status-after (tokens-status-of store)]

               (st/emit! dwu/undo)
               (await (async/wait-for-store store #(not= "during" (:name (shape-of store :rect-3)))
                                            "edit undone"))
               (t/is (= 16 (radius-of store)) "the first undo only reverts the later edit")
               (t/is (= status-after (tokens-status-of store)))

               (st/emit! dwu/undo)
               (await (async/wait-for-store store #(= 8 (radius-of store)) "change undone"))
               (t/is (= status-before (tokens-status-of store)))
               (t/is (= "before" (:name (shape-of store :rect-2)))
                     "the second undo reverts the change and what it propagated, no more")

               (st/emit! dwu/undo)
               (await (async/wait-for-store store #(not= "before" (:name (shape-of store :rect-2)))
                                            "first edit undone"))))))
        (rx/dispose! store)))))

(t/deftest ^:async test-undo-during-propagation-waits-for-the-action
  (doseq [[what change] desktop-changes]
    (t/testing (str what ": an undo pressed before the propagation ends undoes the change")
      (let [store (ths/setup-store (setup-file-for-undo))]
        (await
         (mock/with-mocks* {st/state store st/stream (ptk/input-stream store)}
           (await (start-store store))
           (st/emit! (rename :rect-2 "before"))
           (let [status-before (tokens-status-of store)]
             (st/emit! (change) dwu/undo)
             (await (wait-action-done store "action done"))
             (await (async/wait-for-store store #(= 8 (radius-of store)) "change undone"))
             (t/is (= status-before (tokens-status-of store))))
           (t/is (= "before" (:name (shape-of store :rect-2)))
                 "the previous action stays")
           (t/is (= 0 (get-in @store [:workspace-undo :index])))))
        (rx/dispose! store)))))

(defn- assert-released
  [store]
  (t/is (empty? (pending-actions store)) "the undo action is finished")
  (t/is (not (::dwsh/update-shapes-buffer @store)) "the shape buffer is stopped")
  (t/is (nil? (get-in @store [:workspace-undo :transaction])) "no undo transaction is open")
  (t/is (empty? (get-in @store [:workspace-undo :transactions-pending]))))

(defn- ^:async assert-later-edit-undoes-alone
  [store]
  (st/emit! (rename :rect-3 "after"))
  (await (async/wait-for-store store #(= "after" (:name (shape-of store :rect-3))) "edited"))
  (let [themes (active-themes store)]
    (st/emit! dwu/undo)
    (await (async/wait-for-store store #(not= "after" (:name (shape-of store :rect-3)))
                                 "later edit undone"))
    (t/is (= themes (active-themes store)) "the undo only reverts the later edit")))

(t/deftest ^:async test-failed-resolution-releases-the-action
  (let [errors (atom [])
        store  (ths/setup-store (setup-file-for-undo) {:on-error #(swap! errors conj %)})]
    (await
     (mock/with-mocks* {st/state store st/stream (ptk/input-stream store)}
       (await (start-store store))
       (await
        (mock/with-mocks* {sd/resolve-tokens (mock/stub (fn [_] (rx/throw (ex-info "resolver failed" {}))))}
          (st/emit! (dwtl/set-token-theme-active (thi/id :desktop) true))
          (await (async/wait-for #(seq @errors) "the error is reported"))))
       (await (wait-action-done store "action done"))
       (assert-released store)
       (t/is (contains? (active-themes store) (thi/id :desktop)))
       (await (assert-later-edit-undoes-alone store))
       (st/emit! dwu/undo)
       (await (async/wait-for-store store #(not (contains? (active-themes store) (thi/id :desktop)))
                                    "change undone"))))
    (rx/dispose! store)))

(t/deftest ^:async test-failed-propagation-releases-the-buffer-and-the-action
  (doseq [[what event] {"theme switch" #(dwtl/set-token-theme-active (thi/id :desktop) true)
                        "plain propagation" #(dwtp/propagate-workspace-tokens)}]
    (t/testing what
      (let [errors (atom [])
            store  (ths/setup-store (setup-file-for-undo) {:on-error #(swap! errors conj %)})]
        (await
         (mock/with-mocks* {st/state store st/stream (ptk/input-stream store)}
           (await (start-store store))
           (await
            ;; Fails inside the propagation, once the shape buffer is started
            (mock/with-mocks* {cfh/get-shape-id-root-frame
                               (mock/stub (fn [& _] (throw (ex-info "propagation failed" {}))))}
              (st/emit! (event))
              (await (async/wait-for #(seq @errors) "the error is reported"))))
           (await (wait-action-done store "action done"))
           (assert-released store)
           (await (assert-later-edit-undoes-alone store))))
        (rx/dispose! store)))))

;; ---- Texts resized once their font is loaded

(defn- setup-file-with-text
  "`size` is 14 in the Mobile set and 24 in the Desktop one, applied to the
  font size of the auto-width text `text-1`."
  []
  (-> (tho/sample-file-with-tokens
       :lib-fn #(-> %
                    (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-mobile) :name "mobile"))
                    (ctob/add-set (ctob/make-token-set :id (thi/new-id! :set-desktop) :name "desktop"))
                    (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :mobile)
                                                           :name "Mobile"
                                                           :group "Platform"
                                                           :sets #{"mobile"}))
                    (ctob/add-theme (ctob/make-token-theme :id (thi/new-id! :desktop)
                                                           :name "Desktop"
                                                           :group "Platform"
                                                           :sets #{"desktop"}))
                    (ctob/add-token (thi/id :set-mobile)
                                    (ctob/make-token {:name "size" :value "14" :type :font-size}))
                    (ctob/add-token (thi/id :set-desktop)
                                    (ctob/make-token {:name "size" :value "24" :type :font-size})))
       :status-fn #(ctos/set-tokens-status % #{(thi/id :mobile)} #{(thi/id :set-mobile)}))
      (ctho/add-text :text-1 "Hello" :text-params {:grow-type :auto-width :width 70 :height 14})
      (tho/apply-token-to-shape :text-1 "size" [:font-size] [:font-size] "14")))

(defn- font-size-of
  [store]
  (->> (:content (shape-of store :text-1))
       (txt/content->text+styles)
       (keep (comp :font-size first))
       (first)))

(defn- text-size
  [store]
  (let [{:keys [width height]} (:selrect (shape-of store :text-1))]
    [width height]))

(t/deftest ^:async test-undo-action-waits-for-the-text-resize
  (let [store      (ths/setup-store (setup-file-with-text))
        font-ready (atom true)
        measure    (fn [& _]
                     (let [size (js/parseFloat (or (font-size-of store) "14"))]
                       {:x 0 :y 0 :width (* 5 size) :height size :max-width (* 5 size)}))]
    (await
     (mock/with-mocks* {st/state store
                        st/stream (ptk/input-stream store)
                        wasm.api/get-text-dimensions (mock/stub measure)
                        wasm.fonts/get-content-fonts (mock/stub (fn [_] [{:font-id "sourcesanspro"}]))
                        wasm.fonts/font-ready? (mock/stub (fn [_] @font-ready))}
       (st/emit! (tohs/watch-undo-stack)
                 (dwtp/propagate-workspace-tokens))
       (await (async/wait-for-store store #(= [70 14] (text-size store)) "file propagated"))
       (await (wait-action-done store "nothing pending"))
       (st/emit! dwu/reinitialize-undo)

       ;; The new font stays pending longer than any fixed wait would
       (reset! font-ready false)
       (st/emit! (dwtl/set-token-theme-active (thi/id :desktop) true))
       (await (async/wait-for-store store #(= "24" (font-size-of store)) "font size propagated"))
       (await (js/Promise. (fn [resolve] (js/setTimeout resolve 500))))
       (t/is (= [70 14] (text-size store)) "the text waits for its font")
       (t/is (seq (pending-actions store)) "the undo action waits for the resize")

       (reset! font-ready true)
       (await (async/wait-for-store store #(= [120 24] (text-size store)) "text resized"))
       (await (wait-action-done store "action done"))
       (t/is (= 1 (count (get-in @store [:workspace-undo :items])))
             "the resize is part of the theme switch entry")

       (st/emit! dwu/undo)
       (await (async/wait-for-store store #(= [70 14] (text-size store)) "undone"))
       (t/is (= "14" (font-size-of store)))
       (t/is (contains? (active-themes store) (thi/id :mobile)))
       (t/is (not (contains? (active-themes store) (thi/id :desktop))))))
    (rx/dispose! store)))
