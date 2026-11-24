(ns frontend-tests.tokens.logic.tokens-status-test
  (:require
   [app.common.test-helpers.compositions :as ctho]
   [app.common.test-helpers.ids-map :as thi]
   [app.common.test-helpers.shapes :as cths]
   [app.common.test-helpers.tokens :as tho]
   [app.common.types.tokens-lib :as ctob]
   [app.common.types.tokens-status :as ctos]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.data.workspace.tokens.propagation :as dwtp]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [cljs.test :as t :include-macros true]
   [frontend-tests.helpers.async :as async]
   [frontend-tests.helpers.mock :as mock]
   [frontend-tests.helpers.pages :as thp]
   [frontend-tests.helpers.state :as ths]
   [frontend-tests.tokens.helpers.state :as tohs]
   [potok.v2.core :as ptk]))

(t/use-fixtures :each
  {:before thp/reset-idmap!})

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
